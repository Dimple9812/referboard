package com.referboard.analyzer.prep;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The curated questions in {@code prep/question-bank.yml}: per skill, per skill category, behavioural, to ask. */
public final class QuestionBank {

    /** @param levels for behavioural questions: ALL, INTERN, ENTRY, MID, SENIOR, LEAD, PRINCIPAL */
    public record Question(String q, String why, List<String> covers, List<String> levels) {
        Question withSkill(String display) {
            return new Question(q.replace("{skill}", display), why.replace("{skill}", display), covers, levels);
        }
    }

    private final Map<String, List<Question>> skills;
    private final Map<String, List<Question>> categories;
    private final List<Question> behavioural;
    private final List<Question> toAsk;

    private QuestionBank(Map<String, List<Question>> skills, Map<String, List<Question>> categories,
                         List<Question> behavioural, List<Question> toAsk) {
        this.skills = skills;
        this.categories = categories;
        this.behavioural = behavioural;
        this.toAsk = toAsk;
    }

    public static QuestionBank load() {
        try (InputStream in = QuestionBank.class.getResourceAsStream("/prep/question-bank.yml")) {
            if (in == null) {
                throw new IllegalStateException("prep/question-bank.yml missing");
            }
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            return new QuestionBank(groups(root.get("skills")), groups(root.get("categories")),
                    list(root.get("behavioural")), list(root.get("questionsToAsk")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Questions written for this skill, else its category's (with the skill's name filled in). */
    public List<Question> forSkill(String canonical, String category, String display) {
        List<Question> own = skills.get(canonical);
        if (own != null && !own.isEmpty()) {
            return own;
        }
        List<Question> generic = categories.getOrDefault(category == null ? "default" : category,
                categories.getOrDefault("default", List.of()));
        return generic.stream().map(q -> q.withSkill(display)).toList();
    }

    public java.util.Set<String> skillKeys() {
        return skills.keySet();
    }

    public boolean hasOwnQuestions(String canonical) {
        return skills.containsKey(canonical);
    }

    public List<Question> behavioural(String level) {
        return behavioural.stream().filter(q -> q.levels().contains("ALL") || q.levels().contains(level)).toList();
    }

    public List<Question> toAsk() {
        return toAsk;
    }

    public Optional<Question> first(String canonical, String category, String display) {
        return forSkill(canonical, category, display).stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Question>> groups(Object node) {
        Map<String, List<Question>> out = new LinkedHashMap<>();
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), list(v)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Question> list(Object node) {
        List<Question> out = new ArrayList<>();
        if (node instanceof List<?> xs) {
            for (Object x : xs) {
                Map<String, Object> m = (Map<String, Object>) x;
                out.add(new Question(String.valueOf(m.get("q")), String.valueOf(m.getOrDefault("why", "")),
                        strings(m.get("covers")), strings(m.getOrDefault("levels", List.of("ALL")))));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> strings(Object node) {
        return node instanceof List<?> xs ? xs.stream().map(String::valueOf).toList() : List.of();
    }
}
