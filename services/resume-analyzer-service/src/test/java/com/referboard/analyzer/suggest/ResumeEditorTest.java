package com.referboard.analyzer.suggest;

import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.model.ResumeContent;
import com.referboard.analyzer.suggest.Suggestion.Target;
import com.referboard.analyzer.suggest.Suggestion.Type;
import com.referboard.common.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeEditorTest {

    static Suggestion s(Type type, String before, String after, Target target) {
        return new Suggestion("x-" + type, type, Suggestion.Priority.MEDIUM, "t", "r", before, after, target, List.of(),
                false, null, true, null, Suggestion.Origin.RULES);
    }

    @Test
    void appliesInAStableOrderWhateverTheInputOrder() {
        ResumeContent r = Fixtures.resume();
        List<String> order = List.of("Java", "Spring Boot", "Kafka", "PostgreSQL", "K8s", "Docker", "Redis", "Python");
        ResumeContent out = ResumeEditor.apply(r, List.of(
                s(Type.ADD_SKILL, null, "gRPC", new Target(null, null, "gRPC", null)),
                s(Type.SKILLS_REORDER, null, null, new Target(null, null, null, order)),
                s(Type.SKILL_WORDING, "K8s", "Kubernetes (K8s)", new Target(null, null, "K8s", null)),
                s(Type.BULLET_REWRITE, r.experiences().getFirst().bullets().get(1), "Reduced p99 latency 40% with Redis",
                        new Target(0, 1, null, null)),
                s(Type.SUMMARY_REWRITE, null, "Backend engineer.", Target.none())));
        assertThat(out.skills()).containsExactly("Java", "Spring Boot", "Kafka", "PostgreSQL", "Kubernetes (K8s)",
                "Docker", "Redis", "Python", "gRPC");
        assertThat(out.experiences().getFirst().bullets().get(1)).isEqualTo("Reduced p99 latency 40% with Redis");
        assertThat(out.summary()).isEqualTo("Backend engineer.");
        assertThat(r.skills()).as("original untouched").contains("K8s");
    }

    @Test
    void refusesToEditABulletThatChanged() {
        ResumeContent r = Fixtures.resume();
        assertThatThrownBy(() -> ResumeEditor.apply(r, List.of(s(Type.BULLET_REWRITE, "something else", "new",
                new Target(0, 1, null, null)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("changed");
    }

    @Test
    void adviceIsNeverApplied() {
        ResumeContent r = Fixtures.resume();
        Suggestion advice = new Suggestion("a", Type.GAP_ADVICE, Suggestion.Priority.HIGH, "t", "r", null, null,
                Target.none(), List.of(), false, null, false, null, Suggestion.Origin.RULES);
        assertThat(ResumeEditor.apply(r, List.of(advice))).isEqualTo(r);
    }
}
