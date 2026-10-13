package com.referboard.ingestion.config;

import com.referboard.ingestion.http.PoliteHttpClient;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class HttpConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PoliteHttpClient politeHttpClient(WebClient.Builder builder, IngestionProperties props, Clock clock) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(40))
                .followRedirect(true)
                .compress(true);
        WebClient web = builder
                .clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent())
                // Large boards with full HTML descriptions can be several MB
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)).build())
                .build();
        return new PoliteHttpClient(web, props.userAgent(), props.requestsPerSecondPerHost(),
                props.retryMaxAttempts(), props.retryInitialBackoff(), clock);
    }
}
