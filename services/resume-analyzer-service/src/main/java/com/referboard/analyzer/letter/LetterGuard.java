package com.referboard.analyzer.letter;

import com.referboard.analyzer.suggest.TruthGuard;
import com.referboard.skills.SkillTaxonomy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Checks an AI-written letter before anyone sees it. Unlike a resume edit there's no per-claim confirmation step, so
 * anything new is a rejection: a number not in the resume or the user's note (invented metrics, years, team sizes),
 * a skill the resume doesn't mention, a placeholder, a missing greeting or sign-off, or a letter that's too long.
 * A rejected letter is replaced by the template letter, which can only say what the resume says.
 */
public final class LetterGuard {

    private static final Pattern GREETING = Pattern.compile("^(Dear|Hello|Hi)\\b[^\\n]{0,80},\\s*\\n", Pattern.CASE_INSENSITIVE);
    /** Phrases that promise things the user never said (references, availability, relocation, salary). */
    private static final Pattern PROMISES = Pattern.compile("\\b(references (are )?available|available to start|"
            + "willing to relocate|notice period|expected (ctc|salary)|current ctc)\\b", Pattern.CASE_INSENSITIVE);

    private LetterGuard() {
    }

    public record Verdict(boolean ok, String reason) {
    }

    public static Verdict check(String letter, LetterFacts facts, LetterLength length, SkillTaxonomy taxonomy) {
        if (letter == null || letter.isBlank()) {
            return new Verdict(false, "empty");
        }
        String l = letter.strip();
        int words = l.split("\\s+").length;
        if (l.length() > length.maxChars() || words > length.maxWords()) {
            return new Verdict(false, "too long (" + words + " words)");
        }
        if (!GREETING.matcher(l).find()) {
            return new Verdict(false, "no greeting");
        }
        if (facts.fullName() != null && !l.toLowerCase(Locale.ROOT).contains(facts.fullName().toLowerCase(Locale.ROOT))) {
            return new Verdict(false, "not signed with the user's name");
        }
        if (PROMISES.matcher(l).find()) {
            return new Verdict(false, "promises something the user didn't say");
        }
        TruthGuard.Verdict v = TruthGuard.check(facts.source(), l, taxonomy, length.maxChars());
        if (!v.ok()) {
            return new Verdict(false, v.reason());
        }
        List<String> introduced = v.skillsIntroduced();
        if (!introduced.isEmpty()) {
            return new Verdict(false, "claims skills not on the resume: " + String.join(", ", introduced));
        }
        return new Verdict(true, null);
    }
}
