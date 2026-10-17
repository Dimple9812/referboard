package com.referboard.interview.prep;

import com.referboard.interview.client.AnalyzerClient.Context;
import com.referboard.interview.client.AnalyzerClient.Experience;
import com.referboard.interview.client.AnalyzerClient.SkillMatch;
import com.referboard.interview.client.AnalyzerClient.SkillRef;
import com.referboard.skills.SkillTaxonomy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a prep pack from an analysis, without an LLM. Everything that points at the candidate's experience is one
 * of their own resume bullets, quoted as written: the pack suggests which example to use, never what to claim.
 * Skills the job asks for that the resume lacks get an honest-answer note instead of an example.
 */
public final class PrepBuilder {

    public enum Section { INTRO, TECHNICAL, ROLE, BEHAVIOURAL, GAPS, ASK }

    /** One question. {@code evidence} is a bullet from the resume, verbatim; {@code evidenceFrom} says where. */
    public record Item(Section section, String question, String checks, String tip, String evidence,
                       String evidenceFrom, String skill, String origin) {
    }

    private record Bullet(String text, String from, Set<String> skills, String lower) {
    }

    private static final Pattern NUMBER = Pattern.compile("\\d");
    private static final Set<String> SENIOR = Set.of("SENIOR", "LEAD", "PRINCIPAL", "STAFF");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "our", "your", "you", "will", "that",
            "this", "from", "into", "across", "using", "own", "work", "team", "teams", "build", "help", "make");

    private final QuestionBank bank;
    private final SkillTaxonomy taxonomy;

    public PrepBuilder(QuestionBank bank, SkillTaxonomy taxonomy) {
        this.bank = bank;
        this.taxonomy = taxonomy;
    }

    public List<Item> build(Context c) {
        List<Bullet> bullets = bullets(c);
        Set<String> used = new HashSet<>();   // each bullet is suggested at most once, so examples vary
        List<Item> out = new ArrayList<>();
        out.add(intro(c, bullets));
        out.addAll(technical(c, bullets, used));
        out.addAll(role(c, bullets, used));
        out.addAll(behavioural(bullets, used));
        out.addAll(gaps(c));
        out.addAll(asks(c));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------

    private Item intro(Context c, List<Bullet> bullets) {
        Experience now = c.resume().experiences().isEmpty() ? null : c.resume().experiences().getFirst();
        List<String> parts = new ArrayList<>();
        if (now != null && now.title() != null) {
            parts.add("Start with where you are now: " + now.title()
                    + (now.current() && now.company() != null ? " at " + now.company() : "")
                    + (c.yearsOfExperience() != null ? ", " + c.yearsOfExperience() + " years in" : "") + ".");
        }
        List<String> top = c.matchedSkills().stream().filter(SkillMatch::required).map(SkillMatch::label).limit(3).toList();
        if (!top.isEmpty()) {
            parts.add("Then the part of your experience this role cares about: " + list(top) + ".");
        }
        parts.add("Finish with why this role" + (c.job().companyName() != null ? " at " + c.job().companyName() : "")
                + ", in a sentence. Keep it under two minutes.");
        Bullet highlight = bullets.stream().filter(b -> NUMBER.matcher(b.text()).find())
                .filter(b -> b.skills().stream().anyMatch(s -> c.parsedJd().requiredSkills().contains(s)))
                .findFirst().orElse(bullets.isEmpty() ? null : bullets.getFirst());
        return new Item(Section.INTRO, "Tell me about yourself.", "A clear, short story that connects your experience to this job.",
                String.join(" ", parts), highlight == null ? null : highlight.text(),
                highlight == null ? null : highlight.from(), null, "RULES");
    }

    private List<Item> technical(Context c, List<Bullet> bullets, Set<String> used) {
        // Skills the job requires, the ones the resume shows first; then preferred ones the resume shows.
        Set<String> missing = new HashSet<>();
        c.missingSkills().required().forEach(s -> missing.add(s.name()));
        List<String> skills = new ArrayList<>();
        c.matchedSkills().stream().filter(SkillMatch::required).map(SkillMatch::name).forEach(skills::add);
        c.parsedJd().requiredSkills().stream().filter(s -> !skills.contains(s) && !missing.contains(s)).forEach(skills::add);
        c.matchedSkills().stream().filter(m -> !m.required()).map(SkillMatch::name)
                .filter(s -> !skills.contains(s)).forEach(skills::add);
        List<Item> out = new ArrayList<>();
        int i = 0;
        for (String skill : skills.stream().limit(6).toList()) {
            String display = taxonomy.display(skill);
            Optional<Bullet> ev = bullets.stream().filter(b -> b.skills().contains(skill) && !used.contains(b.text()))
                    .max((a, b) -> Integer.compare(score(a), score(b)));
            ev.ifPresent(b -> used.add(b.text()));
            Optional<SkillMatch> match = c.matchedSkills().stream().filter(m -> m.name().equals(skill)).findFirst();
            String tip = ev.isPresent()
                    ? "Answer from your own work: the example below shows you using " + display + "."
                    : match.isPresent()
                    ? display + " is on your resume but not in a role's bullets. Be ready to say where you used it."
                    : "Prepare this one: the job asks for " + display + ".";
            int n = i < 3 ? 2 : 1;
            for (QuestionBank.Question q : bank.forSkill(skill, display).stream().limit(n).toList()) {
                out.add(new Item(Section.TECHNICAL, q.q(), q.checks(), tip, ev.map(Bullet::text).orElse(null),
                        ev.map(Bullet::from).orElse(null), display, "RULES"));
            }
            i++;
        }
        if (seniorRole(c) && !skills.contains("system design")) {
            QuestionBank.Question q = bank.forSkill("system design", "System design").getFirst();
            out.add(new Item(Section.TECHNICAL, q.q(), q.checks(),
                    "Senior roles usually include a design round. Talk through requirements and trade-offs out loud.",
                    null, null, "System design", "RULES"));
        }
        return out;
    }

    private List<Item> role(Context c, List<Bullet> bullets, Set<String> used) {
        List<Item> out = new ArrayList<>();
        for (String resp : c.parsedJd().responsibilities().stream().filter(r -> r != null && r.length() > 12).limit(4).toList()) {
            String r = resp.strip().replaceAll("[.;]+$", "");
            Set<String> respSkills = taxonomy.extract(r);
            Set<String> respWords = words(r);
            Optional<Bullet> ev = bullets.stream().filter(b -> !used.contains(b.text()))
                    .map(b -> new Object[]{b, overlap(b, respSkills, respWords)})
                    .filter(x -> (int) x[1] >= 2)
                    .max((a, b) -> Integer.compare((int) a[1], (int) b[1]))
                    .map(x -> (Bullet) x[0]);
            ev.ifPresent(b -> used.add(b.text()));
            out.add(new Item(Section.ROLE, "This role involves: \"" + r + "\". How have you done this, or how would you go about it?",
                    "Whether your experience maps to the day-to-day work.",
                    ev.isPresent() ? "Your closest example is below. Describe what you did, how, and what changed because of it."
                            : "Nothing on your resume matches this closely. Describe your approach step by step, and say what similar work you've done.",
                    ev.map(Bullet::text).orElse(null), ev.map(Bullet::from).orElse(null), null, "RULES"));
        }
        return out;
    }

    private List<Item> behavioural(List<Bullet> bullets, Set<String> used) {
        List<Item> out = new ArrayList<>();
        for (QuestionBank.Behavioural q : bank.behavioural().stream().limit(5).toList()) {
            // Prefer a story not suggested yet; a good story can still answer a different question.
            Optional<Bullet> ev = bestFor(q, bullets.stream().filter(b -> !used.contains(b.text())).toList())
                    .or(() -> bestFor(q, bullets));
            ev.ifPresent(b -> used.add(b.text()));
            out.add(new Item(Section.BEHAVIOURAL, q.q(), q.checks(),
                    ev.isPresent() ? "Use the STAR shape: the situation, your task, what you did, and the result. This bullet could be your story."
                            : "Use the STAR shape: the situation, your task, what you did, and the result. Pick a real example before the interview.",
                    ev.map(Bullet::text).orElse(null), ev.map(Bullet::from).orElse(null), null, "RULES"));
        }
        return out;
    }

    private static Optional<Bullet> bestFor(QuestionBank.Behavioural q, List<Bullet> pool) {
        return pool.stream()
                .map(b -> new Object[]{b, q.keywords().stream().mapToInt(k -> b.lower().contains(k.toLowerCase(Locale.ROOT)) ? 1 : 0).sum()})
                .filter(x -> (int) x[1] > 0)
                .max((a, b) -> Integer.compare((int) a[1], (int) b[1]))
                .map(x -> (Bullet) x[0]);
    }

    private List<Item> gaps(Context c) {
        List<Item> out = new ArrayList<>();
        for (SkillRef missing : c.missingSkills().required().stream().limit(4).toList()) {
            String category = taxonomy.lookup(missing.name()).map(SkillTaxonomy.Skill::category).orElse(null);
            Optional<String> adjacent = c.matchedSkills().stream()
                    .filter(m -> category != null && taxonomy.lookup(m.name()).map(SkillTaxonomy.Skill::category)
                            .filter(category::equals).isPresent())
                    .map(SkillMatch::label).findFirst();
            out.add(new Item(Section.GAPS, "Have you worked with " + missing.label() + "?",
                    "Honesty, and how quickly you'd learn it.",
                    "Say plainly that you haven't used " + missing.label() + " at work. Then connect it to the closest thing you have done"
                            + adjacent.map(a -> " (for example, " + a + ")").orElse("")
                            + ", and how you'd get up to speed. Don't claim experience you don't have.",
                    null, null, missing.label(), "RULES"));
        }
        return out;
    }

    private List<Item> asks(Context c) {
        boolean senior = seniorRole(c);
        List<QuestionBank.Ask> general = bank.asks().stream().filter(a -> a.when() == null).toList();
        List<QuestionBank.Ask> chosen = new ArrayList<>(general.stream().limit(senior ? 4 : 6).toList());
        if (senior) {
            bank.asks().stream().filter(a -> "senior".equals(a.when())).forEach(chosen::add);
        }
        return chosen.stream().map(a -> new Item(Section.ASK, a.q(), null, null, null, null, null, "RULES")).toList();
    }

    // ---------------------------------------------------------------------------------------------------------

    private List<Bullet> bullets(Context c) {
        List<Bullet> out = new ArrayList<>();
        for (Experience e : c.resume().experiences()) {
            String from = e.title() == null ? e.company() : e.company() == null ? e.title() : e.title() + " at " + e.company();
            for (String b : e.bullets()) {
                if (b != null && !b.isBlank()) {
                    String t = b.strip();
                    out.add(new Bullet(t, from, taxonomy.extract(t), t.toLowerCase(Locale.ROOT)));
                }
            }
        }
        return out;
    }

    private boolean seniorRole(Context c) {
        return (c.parsedJd().seniority() != null && SENIOR.contains(c.parsedJd().seniority()))
                || (c.parsedJd().minYears() != null && c.parsedJd().minYears() >= 4);
    }

    private static int score(Bullet b) {
        return b.skills().size() + (NUMBER.matcher(b.text()).find() ? 2 : 0);
    }

    private static int overlap(Bullet b, Set<String> skills, Set<String> words) {
        int n = 0;
        for (String s : skills) {
            n += b.skills().contains(s) ? 2 : 0;
        }
        for (String w : words(b.text())) {
            n += words.contains(w) ? 1 : 0;
        }
        return n;
    }

    private static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+")) {
            if (w.length() > 3 && !STOP.contains(w)) {
                out.add(w.endsWith("s") ? w.substring(0, w.length() - 1) : w);
            }
        }
        return out;
    }

    private static String list(List<String> xs) {
        return xs.size() == 1 ? xs.getFirst() : String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.getLast();
    }
}
