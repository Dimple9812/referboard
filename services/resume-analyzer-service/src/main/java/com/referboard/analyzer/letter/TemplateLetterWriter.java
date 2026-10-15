package com.referboard.analyzer.letter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A plain, honest letter built only from {@link LetterFacts}: the role, the user's current title and years, skills
 * the resume shows that the posting asks for, two or three of their own bullets turned into sentences, and their own
 * reason for applying if they gave one. No adjectives about the company, no claims the resume doesn't make.
 */
public final class TemplateLetterWriter {

    /** Common resume verbs that aren't regular "-ed" forms. */
    private static final Set<String> PAST = Set.of("built", "led", "ran", "wrote", "made", "cut", "drove", "grew",
            "took", "set", "won", "brought", "taught", "kept", "began", "rebuilt", "shipped", "owned", "spoke", "sold",
            "held", "found", "chose", "gave", "rewrote", "overtook", "split", "beat", "put", "shut", "met");

    /** "-ed" words that usually start a noun phrase in a bullet ("Distributed tracing for …"), not an action. */
    private static final Set<String> NOT_ACTIONS = Set.of("distributed", "advanced", "experienced", "skilled",
            "dedicated", "detailed", "based", "related", "varied", "seasoned", "certified", "specialized",
            "specialised", "need", "speed", "seed", "feed", "embedded");

    private TemplateLetterWriter() {
    }

    public static String write(LetterFacts f, LetterLength length) {
        return length == LetterLength.SHORT ? shortLetter(f) : standard(f);
    }

    static String standard(LetterFacts f) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(greeting(f));
        paragraphs.add(join(applying(f), whoIAm(f), skillsSentence(f)));
        String examples = examples(f, 2, true);
        if (!examples.isEmpty()) {
            paragraphs.add(examples);
        }
        if (f.motivation() != null) {
            paragraphs.add(sentence(f.motivation()));
        }
        paragraphs.add("I'd welcome the chance to talk about the role and what I could bring"
                + (f.company() == null ? " to the team" : " to " + f.company()) + ". Thank you for your time.");
        paragraphs.add(signOff(f));
        return String.join("\n\n", paragraphs);
    }

    static String shortLetter(LetterFacts f) {
        List<String> paragraphs = new ArrayList<>();
        paragraphs.add(greeting(f));
        paragraphs.add(join(applying(f), whoIAm(f), examples(f, 1, false),
                f.motivation() == null ? null : sentence(f.motivation()), "Thank you for considering my application."));
        paragraphs.add(signOff(f));
        return String.join("\n\n", paragraphs);
    }

    private static String greeting(LetterFacts f) {
        return f.company() == null ? "Dear Hiring Manager," : "Dear Hiring Team at " + f.company() + ",";
    }

    private static String applying(LetterFacts f) {
        if (f.jobTitle() != null && f.company() != null) {
            return "I'm applying for the " + f.jobTitle() + " role at " + f.company() + ".";
        }
        if (f.jobTitle() != null) {
            return "I'm applying for the " + f.jobTitle() + " role.";
        }
        return f.company() != null ? "I'm applying for the open role at " + f.company() + "." : "I'm applying for this role.";
    }

    private static String whoIAm(LetterFacts f) {
        if (f.currentTitle() == null) {
            return f.yearsText() == null ? null : "I have " + f.yearsText() + " years of experience.";
        }
        StringBuilder sb = new StringBuilder("I'm ").append(article(f.currentTitle())).append(' ').append(f.currentTitle());
        if (f.yearsText() != null) {
            sb.append(" with ").append(f.yearsText()).append(" years of experience");
        }
        if (f.currentCompany() != null) {
            sb.append(", currently at ").append(f.currentCompany());
        }
        return sb.append('.').toString();
    }

    private static String skillsSentence(LetterFacts f) {
        List<String> skills = f.matchedSkills().stream().limit(4).toList();
        if (skills.isEmpty()) {
            return null;
        }
        return "I work with " + list(skills) + (skills.size() == 1 ? ", which" : ", all of which")
                + " this role asks for.";
    }

    /** "At Lumen Pay, I built …. Before that, at Kite, I led …." from the user's own bullets. */
    static String examples(LetterFacts f, int max, boolean separateParagraph) {
        List<String> out = new ArrayList<>();
        String lastCompany = null;
        for (LetterFacts.Evidence e : f.evidence()) {
            if (out.size() == max) {
                break;
            }
            String act = asSentence(e.bullet());
            if (act == null) {
                continue;   // not an action ("Tech: Java, Kafka"): it wouldn't read as a sentence
            }
            if (e.company() == null || e.company().equals(lastCompany)) {
                out.add(out.isEmpty() ? act : "I also " + act.substring(2));
            } else if (out.isEmpty() && e.company().equals(f.currentCompany()) && !separateParagraph) {
                out.add("There, " + act);   // the sentence before already said "currently at <company>"
            } else {
                out.add((out.isEmpty() ? "At " : "Before that, at ") + e.company() + ", " + act);
            }
            lastCompany = e.company();
        }
        return String.join(" ", out);
    }

    /** "Cut p99 latency by 40%" → "I cut p99 latency by 40%."; null when the bullet doesn't start with a verb. */
    static String asSentence(String bullet) {
        String b = bullet.strip().replaceAll("^[•·\\-*–]+\\s*", "").replaceAll("[.;\\s]+$", "");
        if (b.isEmpty()) {
            return null;
        }
        String first = b.split("\\s+", 2)[0];
        String lower = first.toLowerCase(Locale.ROOT);
        boolean verb = (lower.length() > 3 && lower.endsWith("ed") && !NOT_ACTIONS.contains(lower)) || PAST.contains(lower);
        return verb && !isAcronym(first) ? "I " + lower + b.substring(first.length()) + "." : null;
    }

    private static boolean isAcronym(String w) {
        return w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT));
    }

    private static String signOff(LetterFacts f) {
        return "Regards,\n" + (f.fullName() == null ? "" : f.fullName());
    }

    static String article(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.matches("^(a|e|i|o|u|sde|sre|ml|hr|fpga|mba|llm).*") && !w.startsWith("uni") && !w.startsWith("user")
                ? "an" : "a";
    }

    private static String list(List<String> xs) {
        if (xs.size() == 1) {
            return xs.getFirst();
        }
        return String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.getLast();
    }

    private static String sentence(String s) {
        String t = s.strip();
        return t.matches(".*[.!?]$") ? t : t + ".";
    }

    private static String join(String... parts) {
        List<String> xs = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                xs.add(p);
            }
        }
        return String.join(" ", xs);
    }
}
