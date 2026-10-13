package com.referboard.analyzer.config;

import com.referboard.common.web.ApiException;
import feign.Response;
import feign.codec.ErrorDecoder;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Upstream errors become API errors: a job that doesn't exist (or isn't visible) is our 404; anything else from
     * job/profile-service is a 502 the user can retry, never a stack trace.
     */
    @Bean
    public ErrorDecoder feignErrorDecoder() {
        return (methodKey, response) -> {
            int status = response.status();
            if (status == 404) {
                return ApiException.notFound("Upstream resource", methodKey);
            }
            if (status == 401 || status == 403) {
                return new ApiException(HttpStatus.valueOf(status), "UPSTREAM_DENIED",
                        "Not allowed to read this data from " + service(response));
            }
            log.warn("Upstream call {} failed with HTTP {}", methodKey, status);
            return new ApiException(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                    "A dependent service is unavailable. Please try again shortly.");
        };
    }

    private static String service(Response response) {
        return response.request() == null ? "upstream" : response.request().url().replaceAll("\\?.*$", "");
    }

    @Bean
    public OpenAPI analyzerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ReferBoard JD Match Analyzer API").version("v1")
                        .description("Compare a resume with a job description: weighted score, ATS checks, "
                                + "truthful suggestions, and tailored DOCX/PDF versions."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
