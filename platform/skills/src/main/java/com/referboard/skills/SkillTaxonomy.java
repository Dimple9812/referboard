package com.referboard.skills;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Canonical tech-skill names and their aliases, loaded from {@code referboard/skills.yml}.
 *
 * <ul>
 *   <li>{@link #normalize} maps any surface form from a structured list ("K8s", "ReactJS") to its canonical
 *       name ("kubernetes", "react"); unknown skills are cleaned up and kept as-is.</li>
 *   <li>{@link #findMentions} scans free text (a resume bullet, a job description) and returns where each known
 *       skill appears. Ambiguous words ("Go", "C", "Excel") only count when written exactly as configured.</li>
 * </ul>
 * Immutable and thread-safe; use {@link #defaultTaxonomy()} for the bundled dictionary.
 */
public final class SkillTaxonomy {

    public record Skill(String name, String display, String category, List<String> aliases, List<String> strict) {
    }

    /** One occurrence of a skill in text; {@code start}/{@code end} are character offsets. */
    public record Mention(String skill, String surface, int start, int end) {
    }

    // Not preceded by a word char or a symbol that would make it part of a bigger token ("Node.js" ≠ "js").
    private static final String BEFORE = "(?<![\\p{L}\\p{N}+#@/_]|[\\p{L}\\p{N}]\\.)";
    // Not followed by a word char, '+'/'#' ("C++" ≠ "C"), or '.' + word char (".js").
    private static final String AFTER = "(?![\\p{L}\\p{N}+#_]|\\.[\\p{L}\\p{N}])";
    // Strict forms additionally may not run into a hyphen ("C-level" is not the C language).
    private static final String AFTER_STRICT = "(?![\\p{L}\\p{N}+#_&'-]|\\.[\\p{L}\\p{N}])";

    private static volatile SkillTaxonomy defaultInstance;

    private final Map<String, Skill> byCanonical;
    private final Map<String, String> surfaceToCanonical;
    private final Map<String, String> looseSurfaceToCanonical;
    private final Map<String, String> strictSurfaceToCanonical;
    private final Pattern loosePattern;
    private final Pattern strictPattern;

    private SkillTaxonomy(List<Skill> skills) {
        Map<String, Skill> canonical = new LinkedHashMap<>();
        Map<String, String> surfaces = new HashMap<>();
        Map<String, String> loose = new HashMap<>();
        Map<String, String> strict = new HashMap<>();
        for (Skill skill : skills) {
            String name = key(skill.name());
            if (canonical.putIfAbsent(name, skill) != null) {
                throw new IllegalStateException("Duplicate skill: " + name);
            }
            List<String> looseForms = new ArrayList<>();
            looseForms.add(skill.name());
            looseForms.addAll(skill.aliases());
            if (!skill.strict().isEmpty()) {
                // A strict skill's own name/display are ambiguous too; only aliases stay loose.
                looseForms = new ArrayList<>(skill.aliases());
                if (!isAmbiguous(skill)) {
                    looseForms.add(skill.name());
                }
            } else {
                looseForms.add(skill.display());
            }
            for (String form : allForms(skill)) {
                String previous = surfaces.putIfAbsent(key(form), name);
                if (previous != null && !previous.equals(name)) {
                    throw new IllegalStateException("Alias '" + form + "' used by both " + previous + " and " + name);
                }
            }
            looseForms.forEach(f -> loose.put(key(f), name));
            skill.strict().forEach(f -> strict.put(f.trim(), name));
        }
        this.byCanonical = Map.copyOf(canonical);
        this.surfaceToCanonical = Map.copyOf(surfaces);
        this.looseSurfaceToCanonical = Map.copyOf(loose);
        this.strictSurfaceToCanonical = Map.copyOf(strict);
        this.loosePattern = alternation(loose.keySet(), AFTER, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.strictPattern = alternation(strict.keySet(), AFTER_STRICT, 0);
    }

    public static SkillTaxonomy defaultTaxonomy() {
        SkillTaxonomy instance = defaultInstance;
        if (instance == null) {
            synchronized (SkillTaxonomy.class) {
                instance = defaultInstance;
                if (instance == null) {
                    instance = fromClasspath("referboard/skills.yml");
                    defaultInstance = instance;
                }
            }
        }
        return instance;
    }

    public static SkillTaxonomy of(List<Skill> skills) {
        return new SkillTaxonomy(skills);
    }

    @SuppressWarnings("unchecked")
    public static SkillTaxonomy fromClasspath(String resource) {
        try (InputStream in = SkillTaxonomy.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Skill taxonomy not found on classpath: " + resource);
            }
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            List<Map<String, Object>> entries = (List<Map<String, Object>>) root.get("skills");
            List<Skill> skills = entries.stream().map(e -> new Skill(
                    String.valueOf(e.get("name")),
                    String.valueOf(e.getOrDefault("display", e.get("name"))),
                    String.valueOf(e.getOrDefault("category", "other")),
                    strings(e.get("aliases")),
                    strings(e.get("strict")))).toList();
            return new SkillTaxonomy(skills);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read skill taxonomy " + resource, e);
        }
    }

    /** Canonical name for a known skill, otherwise the cleaned-up lowercase input. Null/blank → null. */
    public String normalize(String raw) {
        String k = key(raw);
        if (k.isEmpty()) {
            return null;
        }
        return surfaceToCanonical.getOrDefault(k, k);
    }

    public Optional<Skill> lookup(String raw) {
        String canonical = surfaceToCanonical.get(key(raw));
        return Optional.ofNullable(canonical == null ? null : byCanonical.get(canonical));
    }

    public boolean isKnown(String raw) {
        return surfaceToCanonical.containsKey(key(raw));
    }

    /** Human-friendly label: the configured display name for known skills, else the input. */
    public String display(String raw) {
        return lookup(raw).map(Skill::display).orElse(raw == null ? null : raw.trim());
    }

    /** Distinct canonical skills mentioned in {@code text}, in order of first appearance. */
    public Set<String> extract(String text) {
        return findMentions(text).stream().map(Mention::skill).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Normalizes and de-duplicates a list of skills, preserving order. */
    public List<String> normalizeAll(Collection<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::normalize).filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    /** All occurrences of known skills in {@code text}; overlapping matches resolve to the longest. */
    public List<Mention> findMentions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Mention> found = new ArrayList<>();
        collect(loosePattern, text, found, false);
        collect(strictPattern, text, found, true);
        found.sort(Comparator.comparingInt(Mention::start).thenComparing(m -> -(m.end() - m.start())));
        List<Mention> result = new ArrayList<>();
        int coveredUntil = -1;
        for (Mention m : found) {
            if (m.start() >= coveredUntil) {
                result.add(m);
                coveredUntil = m.end();
            }
        }
        return result;
    }

    public Collection<Skill> all() {
        return byCanonical.values();
    }

    private void collect(Pattern pattern, String text, List<Mention> out, boolean strict) {
        if (pattern == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String surface = matcher.group();
            String canonical = strict ? strictSurfaceToCanonical.get(surface) : looseSurfaceToCanonical.get(key(surface));
            if (canonical != null) {
                out.add(new Mention(canonical, surface, matcher.start(), matcher.end()));
            }
        }
    }

    private static boolean isAmbiguous(Skill skill) {
        // A strict skill whose canonical name is itself one of the strict words ("go", "c", "excel")
        return skill.strict().stream().anyMatch(s -> key(s).equals(key(skill.name())));
    }

    private static List<String> allForms(Skill skill) {
        List<String> forms = new ArrayList<>();
        forms.add(skill.name());
        forms.add(skill.display());
        forms.addAll(skill.aliases());
        forms.addAll(skill.strict());
        return forms;
    }

    private static Pattern alternation(Set<String> forms, String after, int flags) {
        if (forms.isEmpty()) {
            return null;
        }
        String body = forms.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(f -> Pattern.quote(f).replace(" ", "\\E\\s+\\Q"))
                .collect(Collectors.joining("|"));
        return Pattern.compile(BEFORE + "(?:" + body + ")" + after, flags);
    }

    static String key(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        // drop trailing sentence punctuation but keep meaningful symbols (c++, c#, .net, node.js)
        return s.replaceAll("[\\s,;:!?)]+$", "").replaceAll("^[\\s(]+", "");
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<Object>) value).stream().map(String::valueOf).toList();
    }
}
