package com.membershipflow.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void commonErrorResponses_referencesSharedErrorSchema() {
        Operation operation = new Operation().responses(new ApiResponses());

        config.commonErrorResponses().customize(operation, null);

        assertThat(operation.getResponses()).containsKeys("400", "401", "403", "404", "500");
        assertThat(operation.getResponses().get("400").getContent()
                .get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ErrorResponse");
        assertThat(config.membershipFlowOpenApi().getComponents().getSchemas())
                .containsKey("ErrorResponse");
    }
}
