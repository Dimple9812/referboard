package com.referboard.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients
public class ResumeAnalyzerApplication {

    public static void main(String[] args) {
        // Store and compute in UTC regardless of host zone (PostgreSQL rejects legacy ids like Asia/Calcutta).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(ResumeAnalyzerApplication.class, args);
    }
}
