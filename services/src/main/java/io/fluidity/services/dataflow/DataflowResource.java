/*
 *
 *  Copyright (c) 2020. Liquidlabs Ltd <info@liquidlabs.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software  distributed under the License is distributed on an "AS IS"
 *   BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *   See the License for the specific language governing permissions and  limitations under the License.
 *
 */

package io.fluidity.services.dataflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.fluidity.dataflow.ClientDataflowJsonConvertor;
import io.fluidity.dataflow.ClientHistoJsonConvertor;
import io.fluidity.dataflow.ClientLadderJsonConvertor;
import io.fluidity.dataflow.FlowInfo;
import io.fluidity.dataflow.FlowLogHelper;
import io.fluidity.dataflow.histo.FlowStats;
import io.fluidity.search.Search;
import io.fluidity.search.agg.histo.TimeSeries;
import io.fluidity.services.query.FileMeta;
import io.fluidity.services.query.QueryService;
import io.fluidity.services.storage.Storage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.QueryParam;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluidity.dataflow.Model.CORR_DAT_PREFIX;
import static io.fluidity.dataflow.Model.CORR_FLOW_PREFIX;
import static io.fluidity.dataflow.Model.CORR_HIST_PREFIX;
import static io.fluidity.dataflow.Model.CORR_PREFIX;
import static io.fluidity.dataflow.Model.LADDER_HIST_PREFIX;

/**
 * API to build, maintain, and access dataflow models
 */

public class DataflowResource implements DataflowService {


    public static final String MODELS = "_MODEL_";
    public static final char PATH_SEP = '/';
    private final Logger log = LoggerFactory.getLogger(DataflowResource.class);


    @ConfigProperty(name = "dataflow.prefix", defaultValue = "_MODEL_/")
    String modelPrefix;

    @ConfigProperty(name = "cloud.region", defaultValue = "eu-west-2")
    String cloudRegion;

    @ConfigProperty(name = "fluidity.services.query")
    QueryService query;

    DataflowBuilder dataflowBuilder = new DataflowBuilder();

    @ConfigProperty(name = "fluidity.services.storage")
    Storage storage;


    public String id() {
        return DataflowResource.class.getCanonicalName();
    }

    /**
     * Returns status JSON for client to show process
     *
     * @param session
     * @return
     */
    @Override
    public String status(final String tenant, final String session, final String modelName) {
        log.info("/status:{}", session);
        return dataflowBuilder.status(session, modelPrefix + modelName);
    }

    /**
     * Client call used to fan out on FaaS
     *
     * @param tenant
     * @param fileMetas
     * @param search
     * @param apiUrl
     * @return
     * @throws JsonProcessingException
     */
    public static String rewriteCorrelationDataS(final String tenant, final String sessionId, final FileMeta[] fileMetas,
                                                 final Search search, final String apiUrl, final String modelPath) throws JsonProcessingException {
        final String fileMetaString = new ObjectMapper().writeValueAsString(fileMetas);
        final String fileMetaJsonString = URLEncoder.encode(fileMetaString, StandardCharsets.UTF_8);
        final String modelPathJson = URLEncoder.encode(modelPath, StandardCharsets.UTF_8);

        final ResteasyClient client = new ResteasyClientBuilderImpl().build();
        try {
            final ResteasyWebTarget target = client.target(UriBuilder.fromPath(apiUrl));
            final DataflowService proxy = target.proxy(DataflowService.class);
            return proxy.rewriteCorrelationData(tenant, sessionId, fileMetaJsonString, modelPathJson, search);
        } catch (Exception ex) {
            ex.printStackTrace();
            final String failedMessage =
                    "Failed to call onto REST API:" + ex.toString() + " URL:" + apiUrl + "Files:" + fileMetas[0];
            System.out.println(failedMessage);
            return failedMessage;
        } finally {
            client.close();
        }
    }

    @Override
    public List<Map<String, String>> modelDataList(final String tenant, final String session, final String modelNameParam) {
        final String modelName = modelPrefix + modelNameParam;

        log.info("/model:{}", session);

        final long start = System.currentTimeMillis();
        try {
            return dataflowBuilder.getModelDataList(cloudRegion, tenant, session, modelName, storage);
        } finally {
            log.info("Finalize Elapsed:{}", (System.currentTimeMillis() - start));
        }
    }

    @Override
    public String submit(final String tenant, final Search search, String modelNameParam, final String serviceAddress) {

        final String sessionId = search.uid;
        final String modelName = modelPrefix + modelNameParam;
        AtomicInteger rewritten = new AtomicInteger();
        log.info(FlowLogHelper.format(sessionId, "dataflow", "submit", "Starting:" + search));
        final WorkflowRunner runner = new WorkflowRunner(tenant, cloudRegion, storage, query, dataflowBuilder,
                modelName) {
            @Override
            String rewriteCorrelationData(String tenant, String session, FileMeta[] fileMeta, Search search, String modelPath) {
                try {
                    String result = rewriteCorrelationDataS(tenant, sessionId, fileMeta, search, serviceAddress, modelPath);
                    rewritten.incrementAndGet();
                    return result;
                } catch (JsonProcessingException e) {
                    log.warn("Failed to invoke:" + serviceAddress + "/dataflow/rewrite");
                    e.printStackTrace();
                    return "Failed to dispatch to REST:" + e.toString();
                }
            }
        };
        final String userSession = runner.run(search, sessionId);
        log.info(FlowLogHelper.format(sessionId, "dataflow", "submit", "Starting:" + search));

        try {
            return new ObjectMapper().writeValueAsString(userSession + " - rewritten:" + rewritten.toString());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return "{ \"msg\": \"failed\"}";
    }

    @Override
    public String rewriteCorrelationData(final String tenant, final String session, final String fileMetas,
                                         final String modelPathEnc, final Search search) {
        log.info(FlowLogHelper.format(session, "workflow", "rewriteCorrelationData", "Start:" + fileMetas.length()));

        try {
            search.decodeJsonFields();
            final ObjectMapper objectMapper = new ObjectMapper();
            final String modelPath = URLDecoder.decode(modelPathEnc, StandardCharsets.UTF_8);

            final FileMeta[] files = objectMapper.readValue(URLDecoder.decode(fileMetas, StandardCharsets.UTF_8), FileMeta[].class);
            log.info("/file/{}", files[0].filename);

            return dataflowBuilder.extractCorrelationData(session, files, search, storage, cloudRegion, tenant, modelPath);
        } catch (Exception e) {

            e.printStackTrace();
            log.error("/rewriteCorrelation:{} failed:{}", fileMetas, e.toString());
            return "Failed:" + e.toString();
        } finally {
            log.info(FlowLogHelper.format(session, "workflow", "rewriteCorrelationData", "End"));
        }
    }

    @Override
    public List<String> listModels(final String tenant) {

        Set<String> results = new HashSet<>();
        storage.listBucketAndProcess(cloudRegion, tenant, MODELS, (region, itemUrl, itemName, modified, size) -> {
            int from = itemName.indexOf(MODELS) + MODELS.length() + 1;
            int to = itemName.indexOf(PATH_SEP, from);
            if (from > 0 && to > from) {
                results.add(itemName.substring(from, to));
            }
        });
        return new ArrayList<>(results);
    }

    @Override
    public String loadModel(String tenant, String modelName) {
        String modelNameUrl = storage.getBucketName(tenant) + PATH_SEP + MODELS + PATH_SEP + modelName + "/model.json";
        try {
            byte[] bytes = storage.get(cloudRegion, modelNameUrl, 0);
            return new String(bytes);
        } catch (Throwable t) {
            // default to create a new model
            HashMap<Object, Object> model = new HashMap<>();
            model.put("name", modelName);
            model.put("query", "*|*|*|*");
            try {
                return new ObjectMapper().writeValueAsString(model);
            } catch (JsonProcessingException e) {
                log.error("Failed to load:" + e.toString());
                return e.toString();
            }
        }
    }

    @Override
    public String saveModel(String tenant, String modelName, String modelData) {
        String modelNameUrl = storage.getBucketName(tenant) + PATH_SEP + MODELS + PATH_SEP + modelName + "/model.json";

        try {
            try (OutputStream fos = storage.getOutputStream(cloudRegion, tenant, modelNameUrl, 360, System.currentTimeMillis())) {
                fos.write(modelData.getBytes());
            } catch (IOException e) {
                log.warn("Failed to save:", modelName, e);
                return new ObjectMapper().writeValueAsString("Failed to save:" + e.toString());
            }

            return new ObjectMapper().writeValueAsString(modelName);
        } catch (JsonProcessingException e) {
        }
        return "broken";
    }


    @Override
    public String volumeHisto(String tenant, String modelName, Long time) {

        log.info("volumeHisto:" + modelName + " time:" + new Date(time));
        modelName = modelPrefix + modelName;

        List<Map<String, String>> histoUrls = new ArrayList<>();
        storage.listBucketAndProcess(cloudRegion, tenant, modelName, (region1, itemUrl, itemName, modified, size) -> {
            if (itemUrl.contains(CORR_HIST_PREFIX)) {
                histoUrls.add(Map.of("url", itemUrl, "modified", Long.toString(modified), "data", Long.toString(size)));

            }
        });
        ClientHistoJsonConvertor convertor = new ClientHistoJsonConvertor();
        StringBuilder results = new StringBuilder();
        List<TimeSeries<FlowStats>> last = new ArrayList<>();
        histoUrls.stream().forEach(item -> {
            byte[] jsonPayload = storage.get(cloudRegion, item.get("url"), 0);
                TimeSeries<FlowStats> timeSeries = convertor.fromJson(jsonPayload);
                last.add(timeSeries);
                if (timeSeries.start() < time && timeSeries.end() > time) {
                    results.append(convertor.toClientArrays(timeSeries));
                }
        });

        Collections.sort(last, (o1, o2) -> Long.compare(o1.start(), o2.start()));
        if (results.length() == 0 || true) {
            return convertor.toClientArrays(last.get(last.size()-1));
        }
        return results.toString();
    }

    @Override
    public String heatmapHisto(String tenant, String modelName, Long time) {

        log.info("heatmapHisto:" + modelName + " time:" + new Date(time));

        modelName = modelPrefix + modelName;

        List<Map<String, String>> ladderUrls = new ArrayList<>();
        storage.listBucketAndProcess(cloudRegion, tenant, modelName, (region1, itemUrl, itemName, modified, size) -> {
            if (itemUrl.contains(LADDER_HIST_PREFIX)) {
                ladderUrls.add(Map.of("url", itemUrl, "modified", Long.toString(modified), "data", Long.toString(size)));
            }
        });
        ClientLadderJsonConvertor convertor = new ClientLadderJsonConvertor();
        StringBuilder results = new StringBuilder();
        List<TimeSeries<Map<Long, FlowStats>>> last = new ArrayList<>();
        ladderUrls.stream().forEach(item -> {
            byte[] jsonPayload = storage.get(cloudRegion, item.get("url"), 0);
            TimeSeries<Map<Long, FlowStats>> ladder = convertor.fromJson(jsonPayload);
            last.add(ladder);
            if (ladder.start() < time && ladder.end() > time) {
                results.append(convertor.toClientArrays(ladder));
            }
        });
        Collections.sort(last, (o1, o2) -> Long.compare(o1.start(), o2.start()));
        if (results.length() == 0 || true) {
            TimeSeries<Map<Long, FlowStats>> lasty = (TimeSeries<Map<Long, FlowStats>>) last.get(last.size() - 1);

            return convertor.toClientArrays(lasty);
        }
        return results.toString();
    }

    public String dataflows(@QueryParam("tenant") String tenant, @QueryParam("model") final String modelNameParam,
                            @QueryParam("timeX1") Long timeX1, @QueryParam("timeX2") Long timeX2, @QueryParam("valueY") Long valueY){

        log.info("dataflows:" + modelNameParam + " time:" + new Date(timeX1*1000) + " - " + new Date(timeX2*1000));

        String modelName = modelPrefix + modelNameParam;

        // Todo = expose granularity control to the client - for now pull in the whole time period
        ClientDataflowJsonConvertor convertor = new ClientDataflowJsonConvertor(timeX1*1000, timeX2*1000, valueY,
                -1L);//Model.LADDER_GRANULARITY);
        try {

            List<String> matchingFlowsUrls = new ArrayList<>();
            // TODO: improve PREFIXing (instead of using modelName)
            storage.listBucketAndProcess(cloudRegion, tenant, modelName, (region, itemUrl, itemName, modified, size) -> {
                if (itemUrl.contains(CORR_FLOW_PREFIX)) {
                    if (convertor.isMatch(itemName)) {
                        matchingFlowsUrls.add(itemUrl);
                    }
                }
            });
            List<FlowInfo> flows = new ArrayList<>();
            matchingFlowsUrls.forEach(flowUrl -> {
                byte[] bytes = storage.get(cloudRegion, flowUrl, 0);
                FlowInfo[] flow =
                        convertor.fromJson(("[" + new String(bytes, StandardCharsets.UTF_8) + "]").getBytes(StandardCharsets.UTF_8));
                flows.add(flow[0]);
            });
            if (flows.isEmpty()) {
                flows.add(new FlowInfo("empty", List.of("empty"),
                        ImmutableList.of(new Long[] { 1L ,2L})));
            }
            return new String(convertor.toClientFlowsList(flows), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.error("Failed list list dataFlows:" + modelName, t);
            return t.toString();
        }
    }
    @Override
    public String dataflow(final String tenant, final String modelNameParam, final String correlation) {
        log.info("dataflow:" + modelNameParam + " dataflow:" + correlation);

        final String modelName = modelPrefix + modelNameParam;

        try {
            List<String> matchingFlowsUrls = new ArrayList<>();
            storage.listBucketAndProcess(cloudRegion, tenant, modelName, (region, itemUrl, itemName, modified, size) -> {
                if (itemUrl.contains(CORR_DAT_PREFIX) && itemUrl.contains(correlation)) {
                    matchingFlowsUrls.add(itemUrl);
                }
            });
            List<Map<String, Object>> dats = new ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            matchingFlowsUrls.forEach(flowUrl -> {
                byte[] bytes = storage.get(cloudRegion, flowUrl, 0);
                try {
                    dats.add(mapper.readValue(bytes, Map.class));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            return FlowInfo.datsToJson(mapper, dats);
        } catch (Throwable t) {
            log.error("Failed list correlation dats:" + modelName, t);
            return t.toString();
        }
    }

    @Override
    public String correlation(final String tenant, final String modelNameParam, final String correlation) {
        log.info("dataflow:" + modelNameParam + " dataflow:" + correlation);

        final String modelName = modelPrefix + modelNameParam;

        try {
            List<String> matchingFlowsUrls = new ArrayList<>();
            storage.listBucketAndProcess(cloudRegion, tenant, modelName, (region, itemUrl, itemName, modified, size) -> {
                if (itemUrl.contains(CORR_PREFIX) && itemUrl.contains(correlation)) {
                        matchingFlowsUrls.add(itemUrl);
                }
            });
            Map<String, String> dats = new HashMap<>();
            ObjectMapper mapper = new ObjectMapper();
            matchingFlowsUrls.forEach(flowUrl -> {
                byte[] bytes = storage.get(cloudRegion, flowUrl, 0);
                    dats.put("value", new String(bytes));
            });

            return mapper.writeValueAsString(dats);
        } catch (Throwable t) {
            log.error("Failed list correlation dats:" + modelName, t);
            return t.toString();
        }
    }

}
