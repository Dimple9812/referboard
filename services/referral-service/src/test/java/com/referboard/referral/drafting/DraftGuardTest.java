package com.referboard.referral.drafting;

import com.referboard.referral.Fixtures;
import com.referboard.referral.domain.MessageDraft.Kind;
import com.referboard.referral.domain.PersonType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DraftGuardTest {

    private final DraftFacts facts = DraftFacts.of(Fixtures.contact(PersonType.TEAM_MEMBER, "Priya Sharma", null),
            Fixtures.job(), Fixtures.user(), null);

    @Test
    void rejectsNumbersThatAreNotInTheFacts() {
        assertThat(DraftGuard.check(Kind.REFERRAL_REQUEST, "I have 7 years of Java experience.", facts))
                .isEqualTo(new DraftGuard.Result.Rejected("number not in facts: 7"));
        assertThat(DraftGuard.check(Kind.REFERRAL_REQUEST, "I scaled it to 1,000,000 users.", facts))
                .isInstanceOf(DraftGuard.Result.Rejected.class);
        // "12" appears inside the job URL's id 4012345, but that doesn't make it a known fact
        assertThat(DraftGuard.check(Kind.REFERRAL_REQUEST, "I led a team of 12.", facts))
                .isEqualTo(new DraftGuard.Result.Rejected("number not in facts: 12"));
    }

    @Test
    void acceptsNumbersFromTheResumeAndJob() {
        assertThat(DraftGuard.check(Kind.REFERRAL_REQUEST,
                "With 4+ years (4.5) in backend, I cut p99 latency by 40%. Posting: "
                        + "https://boards.greenhouse.io/northwind/jobs/4012345", facts))
                .isInstanceOf(DraftGuard.Result.Ok.class);
    }

    @Test
    void rejectsPlaceholders() {
        assertThat(DraftGuard.check(Kind.CONNECTION_NOTE, "Hi [Name], would love to connect.", facts))
                .isEqualTo(new DraftGuard.Result.Rejected("placeholder"));
    }

    @Test
    void shortensLongNotesAtSentenceBoundaries() {
        String s1 = "Hi Priya, I'm a backend engineer interested in the Senior Backend Engineer role at Northwind.";
        String s2 = " I'd love to connect and learn more about the team.";
        String s3 = " " + "Also a long sentence about many things that goes on and on ".repeat(4).trim() + ".";
        var r = DraftGuard.check(Kind.CONNECTION_NOTE, s1 + s2 + s3, facts);
        assertThat(r).isEqualTo(new DraftGuard.Result.Ok(s1 + s2));
    }

    @Test
    void cutsAtAWordWhenOneSentenceIsTooLong() {
        String out = DraftGuard.shortenToLimit("word ".repeat(100).trim(), 50);
        assertThat(out.length()).isLessThanOrEqualTo(50);
        assertThat(out).endsWith("word…");
    }
}
