/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.receiver.trace.provider;

import java.io.IOException;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.server.GRPCHandlerRegister;
import org.apache.skywalking.oap.server.core.server.JettyHandlerRegister;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleDefine;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.ServiceNotProvidedException;
import org.apache.skywalking.oap.server.receiver.trace.module.TraceModule;
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v5.grpc.TraceSegmentServiceHandler;
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v5.rest.TraceSegmentServletHandler;
import org.apache.skywalking.oap.server.receiver.trace.provider.handler.v6.grpc.TraceSegmentReportServiceHandler;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.ISegmentParserService;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.SegmentParse;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.SegmentParseV2;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.SegmentParserListenerManager;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.SegmentParserServiceImpl;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.endpoint.MultiScopesSpanListener;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.segment.SegmentSpanListener;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.service.ServiceMappingSpanListener;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.standardization.SegmentStandardizationWorker;

/**
 * @author peng-yongsheng
 */
public class TraceModuleProvider extends ModuleProvider {

    private final TraceServiceModuleConfig moduleConfig;
    private SegmentParse.Producer segmentProducer;
    private SegmentParseV2.Producer segmentProducerV2;

    public TraceModuleProvider() {
        this.moduleConfig = new TraceServiceModuleConfig();
    }

    @Override public String name() {
        return "default";
    }

    @Override public Class<? extends ModuleDefine> module() {
        return TraceModule.class;
    }

    @Override public ModuleConfig createConfigBeanIfAbsent() {
        return moduleConfig;
    }

    @Override public void prepare() throws ServiceNotProvidedException {
        SegmentParserListenerManager listenerManager = new SegmentParserListenerManager();
        listenerManager.add(new MultiScopesSpanListener.Factory());
        listenerManager.add(new ServiceMappingSpanListener.Factory());
        listenerManager.add(new SegmentSpanListener.Factory(moduleConfig.getSampleRate()));

        segmentProducer = new SegmentParse.Producer(getManager(), listenerManager);

        listenerManager = new SegmentParserListenerManager();
        listenerManager.add(new MultiScopesSpanListener.Factory());
        listenerManager.add(new ServiceMappingSpanListener.Factory());
        listenerManager.add(new SegmentSpanListener.Factory(moduleConfig.getSampleRate()));

        segmentProducerV2 = new SegmentParseV2.Producer(getManager(), listenerManager);

        this.registerServiceImplementation(ISegmentParserService.class, new SegmentParserServiceImpl(segmentProducerV2));
    }

    @Override public void start() throws ModuleStartException {
        GRPCHandlerRegister grpcHandlerRegister = getManager().find(CoreModule.NAME).provider().getService(GRPCHandlerRegister.class);
        JettyHandlerRegister jettyHandlerRegister = getManager().find(CoreModule.NAME).provider().getService(JettyHandlerRegister.class);
        try {

            grpcHandlerRegister.addHandler(new TraceSegmentServiceHandler(segmentProducer));
            grpcHandlerRegister.addHandler(new TraceSegmentReportServiceHandler(segmentProducerV2));
            jettyHandlerRegister.addHandler(new TraceSegmentServletHandler(segmentProducer));

            SegmentStandardizationWorker standardizationWorker = new SegmentStandardizationWorker(segmentProducer, moduleConfig.getBufferPath() + "v5", moduleConfig.getBufferOffsetMaxFileSize(), moduleConfig.getBufferDataMaxFileSize(), moduleConfig.isBufferFileCleanWhenRestart());
            segmentProducer.setStandardizationWorker(standardizationWorker);

            SegmentStandardizationWorker standardizationWorker2 = new SegmentStandardizationWorker(segmentProducer, moduleConfig.getBufferPath(), moduleConfig.getBufferOffsetMaxFileSize(), moduleConfig.getBufferDataMaxFileSize(), moduleConfig.isBufferFileCleanWhenRestart());
            segmentProducerV2.setStandardizationWorker(standardizationWorker2);
        } catch (IOException e) {
            throw new ModuleStartException(e.getMessage(), e);
        }
    }

    @Override public void notifyAfterCompleted() {

    }

    @Override public String[] requiredModules() {
        return new String[] {CoreModule.NAME};
    }
}
