package com.referboard.referral.service;

import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.PersonType;
import com.referboard.referral.domain.UserSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Who to reach for a job, each with a LinkedIn search the user runs themselves. */
@Service
public class SuggestionService {

    static final int MAX_EX_COLLEAGUE_COMPANIES = 3;

    /** Words that describe level rather than the team, dropped from team-member searches. */
    private static final Set<String> LEVEL_WORDS = Set.of("senior", "sr", "junior", "jr", "lead", "staff",
            "principal", "associate", "intern", "trainee", "i", "ii", "iii", "iv", "1", "2", "3", "head", "chief",
            "manager", "mgr", "director", "vp", "-", "–", "/", "&");

    private final SnapshotService snapshots;

    public SuggestionService(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    public record Suggestion(PersonType personType, String title, String why, String searchKeywords,
                             String linkedinSearchUrl, String tip) {
    }

    public record Suggestions(UUID jobId, String jobTitle, String companyName, List<Suggestion> people,
                              String notice) {
    }

    public Suggestions suggest(UUID userId, UUID jobId) {
        JobSnapshot job = snapshots.job(jobId);
        UserSnapshot user = snapshots.user(userId);
        return build(job, user);
    }

    static Suggestions build(JobSnapshot job, UserSnapshot user) {
        String company = job.getCompanyName();
        List<Suggestion> out = new ArrayList<>();
        out.add(suggestion(PersonType.RECRUITER, "Recruiters and talent partners",
                "They own the pipeline for this role and can tell you whether it's still open.",
                company + " recruiter",
                "Look for titles like Technical Recruiter or Talent Acquisition; prefer people hiring for engineering."));
        out.add(suggestion(PersonType.ENGINEERING_MANAGER, "Engineering managers",
                "A hiring manager's referral carries the most weight; keep the note short and specific.",
                company + " engineering manager",
                "Pick managers whose team matches the role; a referral from the wrong org helps less."));
        String role = roleWords(job.getTitle());
        out.add(suggestion(PersonType.TEAM_MEMBER, "People in a similar role",
                "Engineers on the team can describe the work and refer you through the internal portal.",
                (company + " " + role).trim(),
                "Mention the specific role and one skill you share; ask for a referral only after they accept."));
        if (user != null && notBlank(user.getCollege())) {
            out.add(suggestion(PersonType.ALUMNI, "Alumni from " + user.getCollege(),
                    "A shared college is the warmest common ground with someone you don't know yet.",
                    company + " " + user.getCollege(),
                    "Say which college you share up front; it's the reason they'll read on."));
        }
        if (user != null) {
            int n = 0;
            for (String past : user.getPastCompanies()) {
                if (past.equalsIgnoreCase(company)) {
                    continue;
                }
                out.add(suggestion(PersonType.EX_COLLEAGUE, "People who also worked at " + past,
                        "Former colleagues, or people who know your past employer, can vouch for you more easily.",
                        company + " " + past,
                        "Only call someone a former colleague if you actually worked together."));
                if (++n == MAX_EX_COLLEAGUE_COMPANIES) {
                    break;
                }
            }
        }
        return new Suggestions(job.getJobId(), job.getTitle(), company, out,
                "These links open LinkedIn search in your browser. ReferBoard never visits LinkedIn or sends "
                        + "anything for you: add the people you choose as contacts, and send the drafts yourself.");
    }

    private static Suggestion suggestion(PersonType type, String title, String why, String keywords, String tip) {
        String clean = keywords.replaceAll("[,|;()]", " ").replaceAll("\\s+", " ").trim();
        return new Suggestion(type, title, why, clean, LinkedInUrls.peopleSearch(clean), tip);
    }

    /** "Senior Backend Engineer (Java), Payments" → "Backend Engineer". */
    static String roleWords(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replaceAll("\\(.*?\\)", " ").split("[,|:]| - | – ")[0];
        List<String> words = Arrays.stream(t.trim().split("\\s+"))
                .filter(w -> !w.isBlank() && !LEVEL_WORDS.contains(w.toLowerCase(Locale.ROOT).replaceAll("\\.$", "")))
                .limit(3)
                .toList();
        return words.isEmpty() ? t.trim() : words.stream().collect(Collectors.joining(" "));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
