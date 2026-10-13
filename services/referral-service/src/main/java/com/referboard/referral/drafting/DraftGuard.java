package com.referboard.referral.drafting;

import com.referboard.referral.domain.MessageDraft.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks LLM drafts before the user sees them:
 * <ul>
 *   <li>connection notes must fit LinkedIn's 300-character limit (shortened at a sentence boundary if possible);</li>
 *   <li>no number may appear that isn't in the facts: the cheapest reliable signal of an invented metric,
 *       team size or year of experience;</li>
 *   <li>no unfilled placeholders ("[Your Name]", "{company}").</li>
 * </ul>
 * A draft that fails is replaced by the template draft for the same kind and tone.
 */
public final class DraftGuard {

    public static final int NOTE_LIMIT = 300;
    static final int MIN_NOTE = 60;
    static final int MESSAGE_LIMIT = 1500;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]{1,40}]|\\{[^}]{1,40}}|<[^>]{1,40}>");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private DraftGuard() {
    }

    public sealed interface Result {
        record Ok(String content) implements Result { }

        record Rejected(String reason) implements Result { }
    }

    public static Result check(Kind kind, String content, DraftFacts facts) {
        if (content == null || content.isBlank()) {
            return new Result.Rejected("empty");
        }
        String text = content.strip();
        if (PLACEHOLDER.matcher(text).find()) {
            return new Result.Rejected("placeholder");
        }
        Optional<String> invented = inventedNumber(text, facts.allFactText());
        if (invented.isPresent()) {
            return new Result.Rejected("number not in facts: " + invented.get());
        }
        if (kind == Kind.CONNECTION_NOTE && text.length() > NOTE_LIMIT) {
            String shorter = shortenToLimit(text, NOTE_LIMIT);
            if (shorter.length() < MIN_NOTE) {
                return new Result.Rejected("too long");
            }
            text = shorter;
        }
        if (kind != Kind.CONNECTION_NOTE && text.length() > MESSAGE_LIMIT) {
            return new Result.Rejected("too long");
        }
        return new Result.Ok(text);
    }

    /**
     * The first number in {@code text} that isn't a number in the facts. Compared as whole tokens, so "12" is not
     * excused by a job id like 4012345.
     */
    static Optional<String> inventedNumber(String text, String facts) {
        java.util.Set<String> known = new java.util.HashSet<>();
        Matcher fm = NUMBER.matcher(facts);
        while (fm.find()) {
            known.add(normalizeNumber(fm.group()));
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String n = m.group().replaceAll("[.,]+$", "");
            if (!known.contains(normalizeNumber(n))) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    private static String normalizeNumber(String n) {
        return n.replaceAll("[.,]+$", "").replace(",", "");
    }

    /**
     * Drops whole sentences from the end until the text fits (keeping at least the first); if the first sentence
     * alone is too long, cuts at the last word boundary and adds an ellipsis.
     */
    public static String shortenToLimit(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        List<String> sentences = new ArrayList<>(List.of(SENTENCE_END.split(text.strip())));
        while (sentences.size() > 1) {
            sentences.removeLast();
            String joined = String.join(" ", sentences);
            if (joined.length() <= limit) {
                return joined;
            }
        }
        String s = sentences.getFirst();
        int cut = s.lastIndexOf(' ', limit - 1);
        return s.substring(0, cut > 0 ? cut : limit - 1).replaceAll("[,;:\\s]+$", "") + "…";
    }
}
