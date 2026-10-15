package com.referboard.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    @Test
    void mapsGatewayFailures() {
        assertThat(GatewayErrorHandler.map(NotFoundException.create(true, "Unable to find instance")).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorHandler.map(new RuntimeException(new ConnectException("refused"))).code())
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(GatewayErrorHandler.map(new TimeoutException("slow")).status()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(GatewayErrorHandler.map(new ResponseStatusException(HttpStatus.NOT_FOUND)).message())
                .isEqualTo("No such endpoint");
        assertThat(GatewayErrorHandler.map(new IllegalStateException("bug")).status())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
