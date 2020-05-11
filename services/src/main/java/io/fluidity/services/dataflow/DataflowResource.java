package io.fluidity.services.dataflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluidity.dataflow.LogHelper;
import io.fluidity.search.Search;
import io.fluidity.services.query.FileMeta;
import io.fluidity.services.query.QueryService;
import io.fluidity.services.storage.Storage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds a dataflow by fanning out to Lambdas, returning status on progress
 */
@Path("/dataflow")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DataflowResource implements DataflowService {

    private final Logger log = LoggerFactory.getLogger(DataflowResource.class);

    @ConfigProperty(name = "cloud.region", defaultValue = "eu-west-2")
    String cloudRegion;

    @ConfigProperty(name = "fluidity.services.query")
    QueryService query;

    DataflowBuilder dataflowBuilder = new DataflowBuilder();

    @ConfigProperty(name = "fluidity.services.storage")
    Storage storage;


    @GET
    @Path("/id")
    @Produces(MediaType.TEXT_PLAIN)
    public String id() {
        return DataflowResource.class.getCanonicalName();
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
    public static String rewriteCorrelationData(String tenant, String sessionId, FileMeta[] fileMetas, Search search, String apiUrl, String modelPath) throws JsonProcessingException {
        String fileMetaString = new ObjectMapper().writeValueAsString(fileMetas);
        ResteasyClient client = new ResteasyClientBuilderImpl().build();
        ResteasyWebTarget target = client.target(UriBuilder.fromPath(apiUrl));
        DataflowService proxy = target.proxy(DataflowService.class);
        return proxy.rewriteCorrelationData(tenant, sessionId, fileMetaString, modelPath, search);
    }

    @POST
    @Path("/submit")
    @Override
    public String submit(String tenant, Search search, String serviceAddress, String modelName) {

        String sessionId = "123";
        log.info(LogHelper.format(sessionId, "dataflow", "submit", "Starting:" + search));
        String serviceApiUrl = "somewhere";
        WorkflowRunnerV1 runner = new WorkflowRunnerV1(tenant, dataflowBuilder, tenant + "/" + modelName, query, serviceApiUrl);
        String userSession = runner.run(search, sessionId);
        log.info(LogHelper.format(sessionId, "dataflow", "submit", "Starting:" + search));
        return userSession;
    }

    /**
     * Returns status JSON for client to show process
     *
     * @param session
     * @return
     */
    @GET
    @Path("/status")
    @Override
    public String status(String tenant, String session, String modelName) {
        log.info("/status:{}", session);
        return dataflowBuilder.status(session, modelName);
    }

    @GET
    @Path("/model")
    @Override
    public String model(String tenant, String session, String modelName) {

        log.info("/model:{}", session);

        long start = System.currentTimeMillis();
        try {
            return dataflowBuilder.getModel(cloudRegion, tenant, session, modelName, storage);
        } finally {
            log.info("Finalize Elapsed:{}", (System.currentTimeMillis() - start));
        }
    }

    @Override
    @POST
    @Path("/files/{tenant}/{session}/{files}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public String rewriteCorrelationData(@PathParam("tenant") String tenant, @PathParam("session") String session, @PathParam("files") String fileMetas, @PathParam("modelPath") String modelPath, @MultipartForm Search search) {
        search.decodeJsonFields();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            FileMeta[] files = objectMapper.readValue(URLDecoder.decode(fileMetas, StandardCharsets.UTF_8), FileMeta[].class);
            log.debug("/file/{}", files[0].filename);

            return dataflowBuilder.extractCorrelationData(session, files, search, storage, cloudRegion, tenant, modelPath + "/corr-");
        } catch (Exception e) {
            log.error("/search/file:{} failed:{}", fileMetas, e.toString());
            return "Failed:" + e.toString();
        }
    }

}