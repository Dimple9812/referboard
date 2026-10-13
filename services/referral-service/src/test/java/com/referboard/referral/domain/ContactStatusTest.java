package com.referboard.referral.domain;

import org.junit.jupiter.api.Test;

import static com.referboard.referral.domain.ContactStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ContactStatusTest {

    @Test
    void forwardMovesAndSkipsAreAllowed() {
        assertThat(NOT_CONTACTED.canMoveTo(REQUEST_SENT)).isTrue();
        assertThat(NOT_CONTACTED.canMoveTo(REFERRAL_ASKED)).isTrue();   // already connected outside ReferBoard
        assertThat(CONNECTED.canMoveTo(REFERRED)).isTrue();
    }

    @Test
    void oneStepBackUndoesAMisclick() {
        assertThat(CONNECTED.canMoveTo(REQUEST_SENT)).isTrue();
        assertThat(CONNECTED.canMoveTo(NOT_CONTACTED)).isFalse();
    }

    @Test
    void noResponseOnlyAfterContactAndCanRecover() {
        assertThat(NOT_CONTACTED.canMoveTo(NO_RESPONSE)).isFalse();
        assertThat(REQUEST_SENT.canMoveTo(NO_RESPONSE)).isTrue();
        assertThat(REFERRAL_ASKED.canMoveTo(NO_RESPONSE)).isTrue();
        assertThat(NO_RESPONSE.allowedTargets()).containsExactlyInAnyOrder(REQUEST_SENT, CONNECTED, REFERRAL_ASKED, REFERRED);
    }

    @Test
    void referredIsTerminal() {
        assertThat(REFERRED.allowedTargets()).isEmpty();
    }

    @Test
    void stampsTheTimestampOfTheNewStatus() {
        Contact c = new Contact(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "A", null, null, PersonType.RECRUITER);
        var t = java.time.Instant.parse("2026-10-01T10:00:00Z");
        c.moveTo(CONNECTED, t);
        assertThat(c.getStatus()).isEqualTo(CONNECTED);
        assertThat(c.getConnectedAt()).isEqualTo(t);
        assertThat(c.getRequestSentAt()).isNull();
    }
}
