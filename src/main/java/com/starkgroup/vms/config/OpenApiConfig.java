package com.starkgroup.vms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger UI at /swagger-ui.html - the live contract between frontend and backend.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI vmsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Stark Group VMS API")
                        .version("v2.0")
                        .description("Vehicle Management System v2.0: vehicles, drivers and assignments, documents "
                                + "with expiry tracking, maintenance and the dashboard. Log in via "
                                + "POST /api/v1/auth/login, then click Authorize and paste the accessToken."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
