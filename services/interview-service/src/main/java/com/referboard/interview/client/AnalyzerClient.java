package com.referboard.interview.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

/** resume-analyzer-service, called with the user's forwarded token: it only ever returns that user's analyses. */
@FeignClient(name = "resume-analyzer-service", url = "${referboard.clients.analyzer-service-url:}")
public interface AnalyzerClient {

    @GetMapping("/api/analyses/{id}/context")
    Context context(@PathVariable("id") UUID id);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Context(UUID analysisId, Job job, Jd parsedJd, List<SkillMatch> matchedSkills, Missing missingSkills,
                   String yearsOfExperience, Resume resume) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Job(UUID jobId, String title, String companyName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Jd(String title, String company, String seniority, Integer minYears, List<String> requiredSkills,
              List<String> preferredSkills, List<String> responsibilities) {
        public Jd {
            requiredSkills = requiredSkills == null ? List.of() : requiredSkills;
            preferredSkills = preferredSkills == null ? List.of() : preferredSkills;
            responsibilities = responsibilities == null ? List.of() : responsibilities;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SkillMatch(String name, String label, boolean required, String evidence, String usedIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SkillRef(String name, String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Missing(List<SkillRef> required, List<SkillRef> preferred) {
        public Missing {
            required = required == null ? List.of() : required;
            preferred = preferred == null ? List.of() : preferred;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Resume(String fullName, String headline, String summary, List<String> skills, List<Experience> experiences) {
        public Resume {
            skills = skills == null ? List.of() : skills;
            experiences = experiences == null ? List.of() : experiences;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Experience(String company, String title, boolean current, List<String> bullets) {
        public Experience {
            bullets = bullets == null ? List.of() : bullets;
        }
    }
}
