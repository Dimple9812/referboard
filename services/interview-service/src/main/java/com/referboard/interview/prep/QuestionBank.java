package com.referboard.interview.prep;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The curated questions in {@code referboard/question-bank.yml}. */
public record QuestionBank(Map<String, List<Question>> bySkill, List<Behavioural> behavioural, List<Ask> asks) {

    public record Question(String q, String checks) {
    }

    public record Behavioural(String q, String checks, List<String> keywords) {
    }

    public record Ask(String q, String when) {
    }

    public List<Question> forSkill(String canonical, String display) {
        List<Question> qs = bySkill.get(canonical);
        if (qs != null && !qs.isEmpty()) {
            return qs;
        }
        return bySkill.getOrDefault("any-skill", List.of()).stream()
                .map(q -> new Question(q.q().replace("{skill}", display), q.checks())).toList();
    }

    public boolean covers(String canonical) {
        return bySkill.containsKey(canonical);
    }

    @SuppressWarnings("unchecked")
    public static QuestionBank load() {
        try (InputStream in = new ClassPathResource("referboard/question-bank.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, List<Question>> skills = new java.util.LinkedHashMap<>();
            ((Map<String, List<Map<String, Object>>>) root.get("skills")).forEach((k, v) -> skills.put(k,
                    v.stream().map(m -> new Question(str(m.get("q")), str(m.get("checks")))).toList()));
            List<Behavioural> behavioural = new ArrayList<>();
            for (Map<String, Object> m : (List<Map<String, Object>>) root.get("behavioural")) {
                behavioural.add(new Behavioural(str(m.get("q")), str(m.get("checks")),
                        ((List<Object>) m.getOrDefault("keywords", List.of())).stream().map(String::valueOf).toList()));
            }
            List<Ask> asks = ((List<Map<String, Object>>) root.get("asks")).stream()
                    .map(m -> new Ask(str(m.get("q")), (String) m.get("when"))).toList();
            return new QuestionBank(Map.copyOf(skills), List.copyOf(behavioural), asks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).strip();
    }
}
