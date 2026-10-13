package com.referboard.referral.drafting;

import com.referboard.referral.Fixtures;
import com.referboard.referral.domain.MessageDraft.Kind;
import com.referboard.referral.domain.MessageDraft.Tone;
import com.referboard.referral.domain.PersonType;
import com.referboard.referral.domain.UserSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDrafterTest {

    private static DraftFacts facts(PersonType type, String headline, UserSnapshot user, String context) {
        return DraftFacts.of(Fixtures.contact(type, "Priya Sharma", headline), Fixtures.job(), user, context);
    }

    @ParameterizedTest
    @EnumSource(PersonType.class)
    void everyDraftFitsAndStatesOnlyKnownFacts(PersonType type) {
        DraftFacts f = facts(type, "SDE at Northwind | ex-Flipkart", Fixtures.user(), null);
        List<DraftText> all = new java.util.ArrayList<>(TemplateDrafter.intro(f));
        all.addAll(TemplateDrafter.followUps(f));
        assertThat(all).hasSize(6);
        for (DraftText d : all) {
            if (d.kind() == Kind.CONNECTION_NOTE) {
                assertThat(d.content().length()).as(d.content()).isLessThanOrEqualTo(DraftGuard.NOTE_LIMIT);
            }
            assertThat(DraftGuard.check(d.kind(), d.content(), f)).as(d.content())
                    .isInstanceOf(DraftGuard.Result.Ok.class);
            assertThat(d.content()).startsWith("Hi Priya");
        }
    }

    @Test
    void warmNoteUsesTheTrueHookForAlumniAndExColleagues() {
        String alumni = TemplateDrafter.connectionNote(facts(PersonType.ALUMNI, null, Fixtures.user(), null), Tone.WARM);
        assertThat(alumni).startsWith("Hi Priya, fellow IIT Madras alum here!").contains("Senior Backend Engineer (Java) role at Northwind");

        String exMatched = TemplateDrafter.connectionNote(
                facts(PersonType.EX_COLLEAGUE, "SDE at Northwind | ex-Flipkart", Fixtures.user(), null), Tone.WARM);
        assertThat(exMatched).contains("both been at Flipkart");

        String exUnknown = TemplateDrafter.connectionNote(
                facts(PersonType.EX_COLLEAGUE, "SDE at Northwind", Fixtures.user(), null), Tone.WARM);
        assertThat(exUnknown).doesNotContain("both been at");
    }

    @Test
    void recruiterNoteNamesTheRoleOnce() {
        String note = TemplateDrafter.connectionNote(facts(PersonType.RECRUITER, null, Fixtures.user(), null), Tone.WARM);
        assertThat(note).startsWith("Hi Priya, I came across the Senior Backend Engineer (Java) opening at Northwind.");
        assertThat(note.split("Senior Backend Engineer", -1)).hasSize(2);
    }

    @Test
    void longFactsAreDroppedBeforeExceedingTheLimit() {
        UserSnapshot u = Fixtures.user();
        u.setCurrentTitle("Principal Distributed Systems and Platform Infrastructure Engineering Lead");
        u.setCurrentCompany("An Extremely Long Company Name Private Limited Technologies");
        u.setCollege("Some Very Long Institute of Technology and Science, Deemed University Campus");
        String note = TemplateDrafter.connectionNote(facts(PersonType.ALUMNI, null, u,
                "we spoke briefly after your talk on event sourcing at the Bengaluru Java meetup last spring"), Tone.WARM);
        assertThat(note.length()).isLessThanOrEqualTo(DraftGuard.NOTE_LIMIT);
        assertThat(note).startsWith("Hi Priya, we spoke briefly").endsWith("would love to connect.");
    }

    @Test
    void referralRequestUsesSharedSkillsAndAVerbatimAchievement() {
        String msg = TemplateDrafter.referralRequest(facts(PersonType.TEAM_MEMBER, null, Fixtures.user(), null), Tone.WARM);
        assertThat(msg).contains("Java, Spring Boot and Kafka")       // the job's skills the user has (not PostgreSQL)
                .doesNotContain("Kubernetes")                           // job skill the user lacks: never claimed
                .contains("Cut p99 latency of the payments API by 40%")
                .contains("https://boards.greenhouse.io/northwind/jobs/4012345")
                .contains("4+ years")
                .endsWith("Asha");
    }

    @Test
    void emptyProfileGivesAGenericButHonestDraft() {
        DraftFacts f = facts(PersonType.RECRUITER, null, null, null);
        String msg = TemplateDrafter.referralRequest(f, Tone.WARM);
        assertThat(msg).doesNotContain("A bit about me").doesNotContain("null");
        assertThat(TemplateDrafter.connectionNote(f, Tone.CONCISE)).doesNotContain("null");
    }
}
