package com.referboard.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    void keepsWellFormedIds() {
        assertThat(CorrelationId.sanitizeOrGenerate("abc-123_DEF.456")).isEqualTo("abc-123_DEF.456");
    }

    @Test
    void replacesMissingOrHostileIds() {
        assertThat(CorrelationId.sanitizeOrGenerate(null)).hasSize(36);
        assertThat(CorrelationId.sanitizeOrGenerate("short")).hasSize(36);
        assertThat(CorrelationId.sanitizeOrGenerate("abc\r\nSet-Cookie: x=y")).hasSize(36);
        assertThat(CorrelationId.sanitizeOrGenerate("a".repeat(65))).hasSize(36);
    }
}
