package com.referboard.job;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class JobServiceApplication {

    public static void main(String[] args) {
        // Store and compute in UTC regardless of host zone (PostgreSQL rejects legacy ids like Asia/Calcutta).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(JobServiceApplication.class, args);
    }
}
