package io.fluidity.services.query;

import io.fluidity.services.aws.AWS;
import io.fluidity.services.aws.AwsQueryService;
import io.fluidity.services.fixture.FixturedQueryService;
import io.fluidity.services.server.RocksDBQueryService;
import org.eclipse.microprofile.config.spi.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryFactoryConverter implements Converter<QueryService> {

    private final Logger log = LoggerFactory.getLogger(QueryFactoryConverter.class);
    private static QueryService queryService = null;

    @Override
    public QueryService convert(String mode) {

        if (queryService != null) return queryService;

        log.info(System.getProperty("mode", "not-set"));

        log.info("Mode:" + mode);

        if (mode.equals("SERVER")) {
            queryService = new RocksDBQueryService();
        } else if (mode.equalsIgnoreCase(AWS.CONFIG)) {
            queryService = new AwsQueryService();
        } else {
            queryService = new FixturedQueryService();
        }
        return queryService;
    }
}
