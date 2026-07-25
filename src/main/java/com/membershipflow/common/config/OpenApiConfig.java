package com.membershipflow.common.config;

import com.membershipflow.common.exception.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String ERROR_SCHEMA = "#/components/schemas/ErrorResponse";

    @Bean
    public io.swagger.v3.oas.models.OpenAPI membershipFlowOpenApi() {
        var schemas = ModelConverters.getInstance().read(ErrorResponse.class);
        return new io.swagger.v3.oas.models.OpenAPI()
                .components(new io.swagger.v3.oas.models.Components().schemas(schemas));
    }

    @Bean
    public OperationCustomizer commonErrorResponses() {
        return (operation, handlerMethod) -> {
            addError(operation, "400", "요청 형식 오류");
            addError(operation, "401", "인증 필요");
            addError(operation, "403", "권한 없음");
            addError(operation, "404", "리소스 없음");
            addError(operation, "500", "서버 내부 오류");
            return operation;
        };
    }

    private void addError(io.swagger.v3.oas.models.Operation operation,
                          String status, String description) {
        operation.getResponses().addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA)))));
    }
}
