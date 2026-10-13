package com.referboard.analyzer.suggest;

import com.referboard.analyzer.model.ResumeContent;
import com.referboard.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Applies accepted suggestions to a resume. Edits are applied in a fixed order (skill wording, skills order,
 * added skills, summary, bullets) and each checks that the text it replaces is still there.
 */
public final class ResumeEditor {

    private ResumeEditor() {
    }

    public static ResumeContent apply(ResumeContent content, List<Suggestion> accepted) {
        List<Suggestion> ordered = new ArrayList<>(accepted.stream().filter(Suggestion::applicable).toList());
        ordered.sort(Comparator.comparingInt(s -> order(s.type())));
        ResumeContent c = content;
        for (Suggestion s : ordered) {
            c = switch (s.type()) {
                case SKILL_WORDING -> {
                    List<String> skills = new ArrayList<>(c.skills());
                    int i = indexOfIgnoreCase(skills, s.before());
                    if (i >= 0) {
                        skills.set(i, s.after());
                    }
                    yield c.withSkills(skills);
                }
                case SKILLS_REORDER -> c.withSkills(reorder(c.skills(), s.target().skillsOrder()));
                case ADD_SKILL -> {
                    List<String> skills = new ArrayList<>(c.skills());
                    if (indexOfIgnoreCase(skills, s.after()) < 0) {
                        skills.add(s.after());
                    }
                    yield c.withSkills(skills);
                }
                case SUMMARY_REWRITE -> c.withSummary(s.after());
                case BULLET_REWRITE -> {
                    int e = s.target().experienceIndex();
                    int b = s.target().bulletIndex();
                    if (e >= c.experiences().size() || b >= c.experiences().get(e).bullets().size()
                            || !c.experiences().get(e).bullets().get(b).equals(s.before())) {
                        throw new ApiException(HttpStatus.CONFLICT, "RESUME_CHANGED",
                                "A bullet this suggestion edits has changed. Run the analysis again.");
                    }
                    yield c.withBullet(e, b, s.after());
                }
                default -> c;
            };
        }
        return c;
    }

    /** Orders {@code current} by {@code target}; skills not in the target (e.g. renamed ones) keep their place at the end. */
    static List<String> reorder(List<String> current, List<String> target) {
        if (target == null) {
            return current;
        }
        List<String> out = new ArrayList<>();
        List<String> remaining = new ArrayList<>(current);
        for (String t : target) {
            int i = indexOfIgnoreCase(remaining, t);
            if (i >= 0) {
                out.add(remaining.remove(i));
            } else {
                // renamed by a SKILL_WORDING edit ("Kubernetes (K8s)" for "K8s"): keep its target position
                for (int k = 0; k < remaining.size(); k++) {
                    if (remaining.get(k).toLowerCase(Locale.ROOT).contains("(" + t.trim().toLowerCase(Locale.ROOT) + ")")) {
                        out.add(remaining.remove(k));
                        break;
                    }
                }
            }
        }
        out.addAll(remaining);
        return out;
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        if (value == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).trim().equalsIgnoreCase(value.trim())) {
                return i;
            }
        }
        return -1;
    }

    private static int order(Suggestion.Type t) {
        return switch (t) {
            case SKILL_WORDING -> 0;
            case SKILLS_REORDER -> 1;
            case ADD_SKILL -> 2;
            case SUMMARY_REWRITE -> 3;
            case BULLET_REWRITE -> 4;
            default -> 9;
        };
    }
}
