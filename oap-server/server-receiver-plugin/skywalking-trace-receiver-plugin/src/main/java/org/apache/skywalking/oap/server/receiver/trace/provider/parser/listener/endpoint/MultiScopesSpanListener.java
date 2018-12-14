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

package org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.endpoint;

import java.util.LinkedList;
import java.util.List;
import org.apache.skywalking.apm.network.language.agent.SpanLayer;
import org.apache.skywalking.oap.server.core.Const;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.cache.EndpointInventoryCache;
import org.apache.skywalking.oap.server.core.cache.ServiceInstanceInventoryCache;
import org.apache.skywalking.oap.server.core.cache.ServiceInventoryCache;
import org.apache.skywalking.oap.server.core.source.DetectPoint;
import org.apache.skywalking.oap.server.core.source.EndpointRelation;
import org.apache.skywalking.oap.server.core.source.RequestType;
import org.apache.skywalking.oap.server.core.source.SourceReceiver;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.decorator.ReferenceDecorator;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.decorator.SegmentCoreInfo;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.decorator.SpanDecorator;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.EntrySpanListener;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.ExitSpanListener;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.SpanListener;
import org.apache.skywalking.oap.server.receiver.trace.provider.parser.listener.SpanListenerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.nonNull;

/**
 * Notice, in here, there are following concepts match
 *
 * v5        |   v6
 *
 * 1. Application == Service 2. Server == Service Instance 3. Service == Endpoint
 *
 * @author peng-yongsheng, wusheng
 */
public class MultiScopesSpanListener implements EntrySpanListener, ExitSpanListener {

    private static final Logger logger = LoggerFactory.getLogger(MultiScopesSpanListener.class);

    private final SourceReceiver sourceReceiver;
    private final ServiceInstanceInventoryCache instanceInventoryCache;
    private final ServiceInventoryCache serviceInventoryCache;
    private final EndpointInventoryCache endpointInventoryCache;

    private final List<SourceBuilder> entrySourceBuilders;
    private final List<SourceBuilder> exitSourceBuilders;
    private SpanDecorator entrySpanDecorator;
    private long minuteTimeBucket;

    private MultiScopesSpanListener(ModuleManager moduleManager) {
        this.sourceReceiver = moduleManager.find(CoreModule.NAME).provider().getService(SourceReceiver.class);
        this.entrySourceBuilders = new LinkedList<>();
        this.exitSourceBuilders = new LinkedList<>();
        this.instanceInventoryCache = moduleManager.find(CoreModule.NAME).provider().getService(ServiceInstanceInventoryCache.class);
        this.serviceInventoryCache = moduleManager.find(CoreModule.NAME).provider().getService(ServiceInventoryCache.class);
        this.endpointInventoryCache = moduleManager.find(CoreModule.NAME).provider().getService(EndpointInventoryCache.class);
    }

    @Override public boolean containsPoint(Point point) {
        return Point.Entry.equals(point) || Point.Exit.equals(point);
    }

    @Override
    public void parseEntry(SpanDecorator spanDecorator, SegmentCoreInfo segmentCoreInfo) {
        this.minuteTimeBucket = segmentCoreInfo.getMinuteTimeBucket();

        if (spanDecorator.getRefsCount() > 0) {
            for (int i = 0; i < spanDecorator.getRefsCount(); i++) {
                ReferenceDecorator reference = spanDecorator.getRefs(i);
                SourceBuilder sourceBuilder = new SourceBuilder();
                sourceBuilder.setSourceEndpointId(reference.getParentEndpointId());

                if (spanDecorator.getSpanLayer().equals(SpanLayer.MQ)) {
                    int serviceIdByPeerId = serviceInventoryCache.getServiceId(reference.getNetworkAddressId());
                    int instanceIdByPeerId = instanceInventoryCache.getServiceInstanceId(serviceIdByPeerId, reference.getNetworkAddressId());
                    sourceBuilder.setSourceServiceInstanceId(instanceIdByPeerId);
                    sourceBuilder.setSourceServiceId(serviceIdByPeerId);
                } else {
                    sourceBuilder.setSourceServiceInstanceId(reference.getParentServiceInstanceId());
                    sourceBuilder.setSourceServiceId(instanceInventoryCache.get(reference.getParentServiceInstanceId()).getServiceId());
                }
                sourceBuilder.setDestEndpointId(spanDecorator.getOperationNameId());
                sourceBuilder.setDestServiceInstanceId(segmentCoreInfo.getServiceInstanceId());
                sourceBuilder.setDestServiceId(segmentCoreInfo.getServiceId());
                sourceBuilder.setDetectPoint(DetectPoint.SERVER);
                sourceBuilder.setComponentId(spanDecorator.getComponentId());
                setPublicAttrs(sourceBuilder, spanDecorator);
                entrySourceBuilders.add(sourceBuilder);
            }
        } else {
            SourceBuilder sourceBuilder = new SourceBuilder();
            sourceBuilder.setSourceEndpointId(Const.USER_ENDPOINT_ID);
            sourceBuilder.setSourceServiceInstanceId(Const.USER_INSTANCE_ID);
            sourceBuilder.setSourceServiceId(Const.USER_SERVICE_ID);
            sourceBuilder.setDestEndpointId(spanDecorator.getOperationNameId());
            sourceBuilder.setDestServiceInstanceId(segmentCoreInfo.getServiceInstanceId());
            sourceBuilder.setDestServiceId(segmentCoreInfo.getServiceId());
            sourceBuilder.setDetectPoint(DetectPoint.SERVER);
            sourceBuilder.setComponentId(spanDecorator.getComponentId());

            setPublicAttrs(sourceBuilder, spanDecorator);
            entrySourceBuilders.add(sourceBuilder);
        }
        this.entrySpanDecorator = spanDecorator;
    }

    @Override public void parseExit(SpanDecorator spanDecorator, SegmentCoreInfo segmentCoreInfo) {
        if (this.minuteTimeBucket == 0) {
            this.minuteTimeBucket = segmentCoreInfo.getMinuteTimeBucket();
        }

        SourceBuilder sourceBuilder = new SourceBuilder();

        int peerId = spanDecorator.getPeerId();
        if (peerId == 0) {
            return;
        }
        int destServiceId = serviceInventoryCache.getServiceId(peerId);
        int mappingServiceId = serviceInventoryCache.get(destServiceId).getMappingServiceId();
        int destInstanceId = instanceInventoryCache.getServiceInstanceId(destServiceId, peerId);

        sourceBuilder.setSourceEndpointId(Const.USER_ENDPOINT_ID);
        sourceBuilder.setSourceServiceInstanceId(segmentCoreInfo.getServiceInstanceId());
        sourceBuilder.setSourceServiceId(segmentCoreInfo.getServiceId());
        sourceBuilder.setDestEndpointId(spanDecorator.getOperationNameId());
        sourceBuilder.setDestServiceInstanceId(destInstanceId);
        if (Const.NONE == mappingServiceId) {
            sourceBuilder.setDestServiceId(destServiceId);
        } else {
            sourceBuilder.setDestServiceId(mappingServiceId);
        }
        sourceBuilder.setDetectPoint(DetectPoint.CLIENT);
        sourceBuilder.setComponentId(spanDecorator.getComponentId());
        setPublicAttrs(sourceBuilder, spanDecorator);
        exitSourceBuilders.add(sourceBuilder);
    }

    private void setPublicAttrs(SourceBuilder sourceBuilder, SpanDecorator spanDecorator) {
        long latency = spanDecorator.getEndTime() - spanDecorator.getStartTime();
        sourceBuilder.setLatency((int)latency);
        sourceBuilder.setResponseCode(Const.NONE);
        sourceBuilder.setStatus(!spanDecorator.getIsError());

        switch (spanDecorator.getSpanLayer()) {
            case Http:
                sourceBuilder.setType(RequestType.HTTP);
                break;
            case Database:
                sourceBuilder.setType(RequestType.DATABASE);
                break;
            default:
                sourceBuilder.setType(RequestType.RPC);
                break;
        }

        sourceBuilder.setSourceServiceName(serviceInventoryCache.get(sourceBuilder.getSourceServiceId()).getName());
        sourceBuilder.setSourceServiceInstanceName(instanceInventoryCache.get(sourceBuilder.getSourceServiceInstanceId()).getName());
        sourceBuilder.setSourceEndpointName(endpointInventoryCache.get(sourceBuilder.getSourceEndpointId()).getName());
        sourceBuilder.setDestServiceName(serviceInventoryCache.get(sourceBuilder.getDestServiceId()).getName());
        sourceBuilder.setDestServiceInstanceName(instanceInventoryCache.get(sourceBuilder.getDestServiceInstanceId()).getName());
        sourceBuilder.setDestEndpointName(endpointInventoryCache.get(sourceBuilder.getDestEndpointId()).getName());
    }

    @Override public void build() {
        entrySourceBuilders.forEach(entrySourceBuilder -> {
            entrySourceBuilder.setTimeBucket(minuteTimeBucket);
            sourceReceiver.receive(entrySourceBuilder.toAll());
            sourceReceiver.receive(entrySourceBuilder.toService());
            sourceReceiver.receive(entrySourceBuilder.toServiceInstance());
            sourceReceiver.receive(entrySourceBuilder.toEndpoint());
            sourceReceiver.receive(entrySourceBuilder.toServiceRelation());
            sourceReceiver.receive(entrySourceBuilder.toServiceInstanceRelation());
            EndpointRelation endpointRelation = entrySourceBuilder.toEndpointRelation();
            /**
             * Parent endpoint could be none, because in SkyWalking Cross Process Propagation Headers Protocol v2,
             * endpoint in ref could be empty, based on that, endpoint relation maybe can't be established.
             * So, I am making this source as optional.
             */
            if (endpointRelation != null) {
                sourceReceiver.receive(endpointRelation);
            }
        });

        exitSourceBuilders.forEach(exitSourceBuilder -> {
            if (nonNull(entrySpanDecorator)) {
                exitSourceBuilder.setSourceEndpointId(entrySpanDecorator.getOperationNameId());
            } else {
                exitSourceBuilder.setSourceEndpointId(Const.USER_ENDPOINT_ID);
            }
            exitSourceBuilder.setSourceEndpointName(endpointInventoryCache.get(exitSourceBuilder.getSourceEndpointId()).getName());

            exitSourceBuilder.setTimeBucket(minuteTimeBucket);
            sourceReceiver.receive(exitSourceBuilder.toServiceRelation());
            sourceReceiver.receive(exitSourceBuilder.toServiceInstanceRelation());
        });
    }

    public static class Factory implements SpanListenerFactory {

        @Override public SpanListener create(ModuleManager moduleManager) {
            return new MultiScopesSpanListener(moduleManager);
        }
    }
}
