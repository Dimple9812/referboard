package com.referboard.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The structured resume the analyzer scores, edits (accepted suggestions only) and renders to DOCX/PDF. Same shape
 * as profile-service's parsed resume; dates are {@code YYYY-MM}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeContent(
        String fullName,
        String email,
        String phone,
        String location,
        String headline,
        String summary,
        List<String> skills,
        List<Experience> experiences,
        List<Education> educations,
        List<String> certifications,
        List<String> links
) {
    public ResumeContent {
        skills = skills == null ? List.of() : List.copyOf(skills);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
        educations = educations == null ? List.of() : List.copyOf(educations);
        certifications = certifications == null ? List.of() : List.copyOf(certifications);
        links = links == null ? List.of() : List.copyOf(links);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Experience(String company, String title, String location, String startDate, String endDate,
                             boolean current, List<String> bullets) {
        public Experience {
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
        }

        public Experience withBullets(List<String> newBullets) {
            return new Experience(company, title, location, startDate, endDate, current, newBullets);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(String institution, String degree, String fieldOfStudy, Integer startYear,
                            Integer endYear, String grade) {
    }

    public ResumeContent withSummary(String newSummary) {
        return new ResumeContent(fullName, email, phone, location, headline, newSummary, skills, experiences,
                educations, certifications, links);
    }

    public ResumeContent withSkills(List<String> newSkills) {
        return new ResumeContent(fullName, email, phone, location, headline, summary, newSkills, experiences,
                educations, certifications, links);
    }

    public ResumeContent withBullet(int experienceIndex, int bulletIndex, String bullet) {
        List<Experience> exps = new ArrayList<>(experiences);
        List<String> bullets = new ArrayList<>(exps.get(experienceIndex).bullets());
        bullets.set(bulletIndex, bullet);
        exps.set(experienceIndex, exps.get(experienceIndex).withBullets(bullets));
        return new ResumeContent(fullName, email, phone, location, headline, summary, skills, exps, educations,
                certifications, links);
    }

    /** Plain text in a conventional order, for keyword checks and for versions (which have no source file). */
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        line(sb, fullName);
        line(sb, String.join(" | ", nonBlank(email, phone, location)));
        line(sb, headline);
        if (summary != null && !summary.isBlank()) {
            sb.append("\nSUMMARY\n").append(summary).append('\n');
        }
        if (!skills.isEmpty()) {
            sb.append("\nSKILLS\n").append(String.join(", ", skills)).append('\n');
        }
        if (!experiences.isEmpty()) {
            sb.append("\nEXPERIENCE\n");
            for (Experience e : experiences) {
                line(sb, String.join(" — ", nonBlank(e.title(), e.company())));
                line(sb, String.join(" – ", nonBlank(e.startDate(), e.current() ? "Present" : e.endDate())));
                e.bullets().forEach(b -> sb.append("- ").append(b).append('\n'));
            }
        }
        if (!educations.isEmpty()) {
            sb.append("\nEDUCATION\n");
            for (Education ed : educations) {
                line(sb, String.join(", ", nonBlank(ed.degree(), ed.fieldOfStudy(), ed.institution(),
                        ed.endYear() == null ? null : ed.endYear().toString())));
            }
        }
        if (!certifications.isEmpty()) {
            sb.append("\nCERTIFICATIONS\n");
            certifications.forEach(c -> sb.append("- ").append(c).append('\n'));
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String s) {
        if (s != null && !s.isBlank()) {
            sb.append(s).append('\n');
        }
    }

    private static List<String> nonBlank(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }
}
