package com.referboard.interview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ai.LlmClient;
import com.referboard.interview.prep.AiQuestions;
import com.referboard.interview.prep.PrepBuilder;
import com.referboard.interview.prep.QuestionBank;
import com.referboard.skills.SkillTaxonomy;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PrepBuilder prepBuilder(SkillTaxonomy taxonomy) {
        return new PrepBuilder(QuestionBank.load(), taxonomy);
    }

    @Bean
    public AiQuestions aiQuestions(LlmClient llm, ObjectMapper mapper) {
        return new AiQuestions(llm, mapper);
    }

    @Bean
    public OpenAPI interviewOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ReferBoard Interview Prep API").version("v1")
                        .description("Prep packs built from a JD analysis: likely questions, your own examples, "
                                + "honest answers on gaps, and questions to ask."))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
