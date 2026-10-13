package com.referboard.notification.email;

import com.referboard.events.DailyMatchDigestEvent;
import com.referboard.events.EventMetadata;
import com.referboard.events.ReferralReminderDueEvent;
import com.referboard.notification.config.NotificationProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplatesTest {

    final EmailTemplates templates = new EmailTemplates(new NotificationProperties("https://app.example.com/",
            "https://api.example.com", "ReferBoard <noreply@example.com>", 20, 1000, 5));
    final EventMetadata meta = new EventMetadata(UUID.randomUUID(), "x", 1, Instant.now(), "test", null);
    final UUID contact = UUID.randomUUID();

    ReferralReminderDueEvent reminder(String type, String name, String draft) {
        return new ReferralReminderDueEvent(meta, UUID.randomUUID(), UUID.randomUUID(), contact, UUID.randomUUID(),
                name, "Backend Engineer", "Northwind", type, "Connected with " + name + " 2 days ago — send it.",
                draft, Instant.now());
    }

    @Test
    void reminderEscapesEverythingFromUsersAndLinksToTheContact() {
        RenderedEmail e = templates.reminder("Asha", reminder("SEND_REFERRAL_MESSAGE", "Eve <script>x</script>",
                "Hi Eve,\n\nThanks & regards"), "https://api.example.com/unsub?t=1&c=reminders");
        assertThat(e.subject()).isEqualTo("Send your referral message to Eve <script>x</script>");
        assertThat(e.html()).doesNotContain("<script>").contains("Eve &lt;script&gt;")
                .contains("Hi Eve,<br><br>Thanks &amp; regards")
                .contains("href=\"https://app.example.com/referrals/contacts/" + contact + "\"")
                .contains("https://api.example.com/unsub?t=1&amp;c=reminders")
                .contains("never sends messages for you");
        assertThat(e.text()).contains("Hi Asha,").contains("Hi Eve,\n\nThanks & regards")
                .contains("Unsubscribe from referral reminders: https://api.example.com/unsub?t=1&c=reminders");
        assertThat(e.unsubscribeUrl()).isEqualTo("https://api.example.com/unsub?t=1&c=reminders");
    }

    @Test
    void followUpSubjectAndNoNameGreeting() {
        RenderedEmail e = templates.reminder(null, reminder("FOLLOW_UP", "Rohan", null), "u");
        assertThat(e.subject()).isEqualTo("Time to follow up with Rohan");
        assertThat(e.text()).startsWith("Hi,").doesNotContain("Draft");
    }

    @Test
    void digestListsMatchesBestFirstWithLinks() {
        UUID j1 = UUID.randomUUID();
        var e = templates.digest("Asha", new DailyMatchDigestEvent(meta, UUID.randomUUID(), LocalDate.of(2026, 10, 13),
                List.of(new DailyMatchDigestEvent.MatchSummary(j1, "Backend Engineer", "Northwind", "Bengaluru", true,
                                91, List.of("Java", "Kafka", "Spring Boot", "PostgreSQL", "Redis")),
                        new DailyMatchDigestEvent.MatchSummary(UUID.randomUUID(), "SRE", "Kestrel", "Remote - India",
                                true, 74, List.of()))), "https://api.example.com/u");
        assertThat(e.subject()).isEqualTo("Your top 2 new job matches for Tue, 13 Oct");
        assertThat(e.html()).contains("91%").contains("Bengaluru / Remote").contains("Remote - India")
                .doesNotContain("Remote - India / Remote")
                .contains("Matches: Java, Kafka, Spring Boot, PostgreSQL").doesNotContain("Redis")
                .contains("https://app.example.com/jobs/" + j1);
        assertThat(e.text()).contains("- 91%  Backend Engineer — Northwind (Bengaluru / Remote)");
    }
}
