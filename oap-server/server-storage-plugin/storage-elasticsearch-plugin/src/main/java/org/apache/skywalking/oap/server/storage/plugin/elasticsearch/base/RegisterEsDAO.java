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

package org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base;

import java.io.IOException;
import java.util.Map;
import org.apache.skywalking.oap.server.core.register.RegisterSource;
import org.apache.skywalking.oap.server.core.storage.*;
import org.apache.skywalking.oap.server.library.client.elasticsearch.ElasticSearchClient;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.metrics.max.Max;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.*;

/**
 * @author peng-yongsheng
 */
public class RegisterEsDAO extends EsDAO implements IRegisterDAO {

    private static final Logger logger = LoggerFactory.getLogger(RegisterEsDAO.class);

    private final StorageBuilder<RegisterSource> storageBuilder;

    public RegisterEsDAO(ElasticSearchClient client, StorageBuilder<RegisterSource> storageBuilder) {
        super(client);
        this.storageBuilder = storageBuilder;
    }

    @Override public RegisterSource get(String modelName, String id) throws IOException {
        GetResponse response = getClient().get(modelName, id);
        if (response.isExists()) {
            return storageBuilder.map2Data(response.getSource());
        } else {
            return null;
        }
    }

    @Override public void forceInsert(String modelName, RegisterSource source) throws IOException {
        Map<String, Object> objectMap = storageBuilder.data2Map(source);

        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (String key : objectMap.keySet()) {
            builder.field(key, objectMap.get(key));
        }
        builder.endObject();

        getClient().forceInsert(modelName, source.id(), builder);
    }

    @Override public void forceUpdate(String modelName, RegisterSource source) throws IOException {
        Map<String, Object> objectMap = storageBuilder.data2Map(source);

        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        for (String key : objectMap.keySet()) {
            builder.field(key, objectMap.get(key));
        }
        builder.endObject();

        getClient().forceUpdate(modelName, source.id(), builder);
    }

    @Override public int max(String modelName) throws IOException {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.aggregation(AggregationBuilders.max(RegisterSource.SEQUENCE).field(RegisterSource.SEQUENCE));
        searchSourceBuilder.size(0);
        return getResponse(modelName, searchSourceBuilder);
    }

    private int getResponse(String modelName, SearchSourceBuilder searchSourceBuilder) throws IOException {
        SearchResponse searchResponse = getClient().search(modelName, searchSourceBuilder);
        Max agg = searchResponse.getAggregations().get(RegisterSource.SEQUENCE);

        int id = (int)agg.getValue();
        if (id == Integer.MAX_VALUE || id == Integer.MIN_VALUE) {
            return 1;
        } else {
            return id;
        }
    }
}
