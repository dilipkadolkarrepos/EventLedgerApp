package com.eventledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI eventLedgerOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Ledger API")
                        .description("""
                                REST API for recording and querying financial transaction events. \
                                Features idempotent ingestion, out-of-order tolerance, \
                                and real-time balance computation.""")
                        .version("1.0.0"));
    }
}
