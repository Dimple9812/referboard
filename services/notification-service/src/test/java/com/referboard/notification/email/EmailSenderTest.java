package com.referboard.notification.email;

import com.referboard.notification.service.EmailCategory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSenderTest {

    @Test
    void backoffGrowsFourfold() {
        assertThat(EmailSender.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(EmailSender.backoff(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(EmailSender.backoff(4)).isEqualTo(Duration.ofMinutes(64));
    }

    @Test
    void categoriesParseFromUnsubscribeLinks() {
        assertThat(EmailCategory.fromParam("DIGEST")).contains(EmailCategory.DIGEST);
        assertThat(EmailCategory.fromParam("reminders")).contains(EmailCategory.REMINDERS);
        assertThat(EmailCategory.fromParam("weekly")).isEmpty();
    }
}
