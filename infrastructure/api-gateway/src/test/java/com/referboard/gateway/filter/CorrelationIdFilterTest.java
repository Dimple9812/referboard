package com.referboard.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    @Test
    void keepsSaneIdsAndReplacesOthers() {
        assertThat(CorrelationIdFilter.sanitizeOrGenerate("web-abc12345")).isEqualTo("web-abc12345");
        assertThat(CorrelationIdFilter.sanitizeOrGenerate("x\r\nSet-Cookie: a=b")).hasSize(36);
        assertThat(CorrelationIdFilter.sanitizeOrGenerate("short")).hasSize(36);
        assertThat(CorrelationIdFilter.sanitizeOrGenerate(null)).hasSize(36);
    }
}
