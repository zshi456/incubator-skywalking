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

package org.apache.skywalking.oap.server.core.remote.client;

import io.grpc.testing.GrpcServerRule;
import java.util.concurrent.TimeUnit;
import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.remote.RemoteServiceHandler;
import org.apache.skywalking.oap.server.core.remote.annotation.StreamDataClassGetter;
import org.apache.skywalking.oap.server.core.remote.data.StreamData;
import org.apache.skywalking.oap.server.core.remote.grpc.proto.RemoteData;
import org.apache.skywalking.oap.server.core.worker.*;
import org.apache.skywalking.oap.server.testing.module.*;
import org.junit.*;

import static org.mockito.Mockito.*;

/**
 * @author peng-yongsheng
 */
public class GRPCRemoteClientTestCase {

    private final int nextWorkerId = 1;
    private ModuleManagerTesting moduleManager;
    private StreamDataClassGetter classGetter;
    @Rule public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();

    @Before
    public void before() {
        moduleManager = new ModuleManagerTesting();
        ModuleDefineTesting moduleDefine = new ModuleDefineTesting();
        moduleManager.put(CoreModule.NAME, moduleDefine);

        classGetter = mock(StreamDataClassGetter.class);
        moduleDefine.provider().registerServiceImplementation(StreamDataClassGetter.class, classGetter);

        TestWorker worker = new TestWorker(nextWorkerId);
        WorkerInstances.INSTANCES.put(nextWorkerId, worker);
    }

    @Test
    public void testPush() throws InterruptedException {
        grpcServerRule.getServiceRegistry().addService(new RemoteServiceHandler(moduleManager));

        Address address = new Address("not-important", 11, false);
        GRPCRemoteClient remoteClient = spy(new GRPCRemoteClient(classGetter, address, 1, 10));
        remoteClient.connect();

        doReturn(grpcServerRule.getChannel()).when(remoteClient).getChannel();

        when(classGetter.findIdByClass(TestStreamData.class)).thenReturn(1);

        Class<?> dataClass = TestStreamData.class;
        when(classGetter.findClassById(1)).thenReturn((Class<StreamData>)dataClass);

        for (int i = 0; i < 12; i++) {
            remoteClient.push(nextWorkerId, new TestStreamData());
        }

        TimeUnit.SECONDS.sleep(1);
    }

    public static class TestStreamData extends StreamData {

        private long value;

        @Override public int remoteHashCode() {
            return 0;
        }

        @Override public void deserialize(RemoteData remoteData) {
            this.value = remoteData.getDataLongs(0);
        }

        @Override public RemoteData.Builder serialize() {
            RemoteData.Builder builder = RemoteData.newBuilder();
            builder.addDataLongs(987);
            return builder;
        }
    }

    class TestWorker extends AbstractWorker {

        public TestWorker(int workerId) {
            super(workerId);
        }

        @Override public void in(Object o) {
            TestStreamData streamData = (TestStreamData)o;
            Assert.assertEquals(987, streamData.value);
        }
    }
}
