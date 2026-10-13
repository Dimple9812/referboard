package com.referboard.referral.drafting;

import com.referboard.referral.domain.Contact;
import com.referboard.referral.domain.JobSnapshot;
import com.referboard.referral.domain.PersonType;
import com.referboard.referral.domain.UserSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Everything a draft may say. Both drafters (template and LLM) work only from these facts, and {@link DraftGuard}
 * checks LLM output against them.
 *
 * @param sharedCompany for ex-colleagues: one of the user's employers that the contact's headline also mentions
 * @param userContext   optional, user-written, e.g. "we met at JSConf India 2025"; treated as a true fact
 */
public record DraftFacts(
        String contactName,
        String contactFirstName,
        String contactHeadline,
        PersonType personType,
        String jobTitle,
        String companyName,
        String jobLocation,
        String jobUrl,
        List<String> jobSkills,
        String userFullName,
        String userFirstName,
        String userTitle,
        String userCompany,
        BigDecimal experienceYears,
        String college,
        List<String> pastCompanies,
        List<String> userSkills,
        List<String> sharedSkills,
        List<String> achievements,
        String sharedCompany,
        String userContext
) {

    static final int MAX_SHARED_SKILLS = 3;

    public static DraftFacts of(Contact contact, JobSnapshot job, UserSnapshot user, String userContext) {
        List<String> userSkills = user == null ? List.of() : user.getSkills();
        List<String> pastCompanies = user == null ? List.of() : user.getPastCompanies();
        Set<String> mine = userSkills.stream().map(DraftFacts::key).collect(Collectors.toSet());
        List<String> shared = job.getSkills().stream().filter(s -> mine.contains(key(s)))
                .limit(MAX_SHARED_SKILLS).toList();
        String headline = contact.getHeadline() == null ? "" : contact.getHeadline().toLowerCase(Locale.ROOT);
        String sharedCompany = null;
        if (contact.getPersonType() == PersonType.EX_COLLEAGUE && !headline.isBlank()) {
            List<String> employers = new ArrayList<>(pastCompanies);
            if (user != null && user.getCurrentCompany() != null) {
                employers.addFirst(user.getCurrentCompany());
            }
            sharedCompany = employers.stream().filter(c -> headline.contains(c.toLowerCase(Locale.ROOT)))
                    .findFirst().orElse(null);
        }
        String fullName = user == null ? null : blankToNull(user.getFullName());
        return new DraftFacts(
                contact.getFullName(), firstName(contact.getFullName()), blankToNull(contact.getHeadline()),
                contact.getPersonType(),
                job.getTitle(), job.getCompanyName(), blankToNull(job.getLocation()), blankToNull(job.getApplyUrl()),
                job.getSkills(),
                fullName, fullName == null ? null : firstName(fullName),
                user == null ? null : blankToNull(user.getCurrentTitle()),
                user == null ? null : blankToNull(user.getCurrentCompany()),
                user == null ? null : user.getExperienceYears(),
                user == null ? null : blankToNull(user.getCollege()),
                pastCompanies, userSkills, shared,
                user == null ? List.of() : user.getAchievements(),
                sharedCompany, blankToNull(userContext));
    }

    /** All text the drafts are allowed to draw on, for the number check. */
    public String allFactText() {
        return Stream.of(Stream.of(contactName, contactHeadline, jobTitle, companyName, jobLocation, jobUrl,
                                userFullName, userTitle, userCompany,
                                experienceYears == null ? null : experienceYears.toPlainString(),
                                experienceYearsText(),
                                college, sharedCompany, userContext),
                        jobSkills.stream(), pastCompanies.stream(), userSkills.stream(), achievements.stream())
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Whole years, with "+" for a remainder: 6.3 → "6+", 4.0 → "4" (the figure is computed from job dates, so a
     * decimal would read oddly). Null under one year (not worth stating).
     */
    public String experienceYearsText() {
        if (experienceYears == null || experienceYears.compareTo(BigDecimal.ONE) < 0) {
            return null;
        }
        BigDecimal whole = experienceYears.setScale(0, java.math.RoundingMode.DOWN);
        return whole.toPlainString() + (experienceYears.compareTo(whole) > 0 ? "+" : "");
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].replaceAll("[.,]$", "");
        // "Dr. Asha Rao" → "Asha"; single-letter initials ("A. Kumar") → use the full name
        if (Set.of("dr", "mr", "mrs", "ms", "prof").contains(first.toLowerCase(Locale.ROOT)) && parts.length > 1) {
            first = parts[1];
        }
        return first.length() <= 1 ? name.trim() : first;
    }

    private static String key(String skill) {
        return skill.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]", "");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
