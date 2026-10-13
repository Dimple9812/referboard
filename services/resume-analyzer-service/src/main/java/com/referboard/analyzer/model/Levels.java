package com.referboard.analyzer.model;

import com.referboard.analyzer.model.ParsedJd.DegreeLevel;
import com.referboard.analyzer.model.ParsedJd.Seniority;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/** Seniority from job titles and degree level from degree names (Indian and international naming). */
public final class Levels {

    private static final Pattern INTERN = Pattern.compile("\\b(intern|internship|trainee|apprentice)\\b");
    private static final Pattern JUNIOR = Pattern.compile("\\b(junior|jr\\.?|associate|graduate|entry[- ]level|fresher|"
            + "(sde|engineer|developer|swe)[- ]?(i|1))\\b");
    private static final Pattern MID = Pattern.compile("\\b((sde|engineer|developer|swe)[- ]?(ii|2))\\b");
    private static final Pattern SENIOR = Pattern.compile("\\b(senior|sr\\.?|(sde|engineer|developer|swe)[- ]?(iii|3))\\b");
    private static final Pattern LEAD = Pattern.compile("\\b(lead|tech lead|team lead)\\b");
    private static final Pattern STAFF = Pattern.compile("\\b(staff)\\b");
    private static final Pattern PRINCIPAL = Pattern.compile("\\b(principal|architect|distinguished)\\b");
    private static final Pattern MANAGER = Pattern.compile("\\b(manager|em|engineering manager)\\b");
    private static final Pattern DIRECTOR = Pattern.compile("\\b(director|head of|vp|vice president|cto)\\b");

    private Levels() {
    }

    /** Seniority named in a title, or null if the title doesn't say. Most senior match wins ("Senior Staff"). */
    public static Seniority fromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT);
        if (DIRECTOR.matcher(t).find()) {
            return Seniority.DIRECTOR;
        }
        if (PRINCIPAL.matcher(t).find()) {
            return Seniority.PRINCIPAL;
        }
        if (MANAGER.matcher(t).find() && !t.contains("product manager") && !t.contains("program manager")) {
            return Seniority.MANAGER;
        }
        if (STAFF.matcher(t).find()) {
            return Seniority.STAFF;
        }
        if (LEAD.matcher(t).find()) {
            return Seniority.LEAD;
        }
        if (SENIOR.matcher(t).find()) {
            return Seniority.SENIOR;
        }
        if (MID.matcher(t).find()) {
            return Seniority.MID;
        }
        if (INTERN.matcher(t).find()) {
            return Seniority.INTERN;
        }
        if (JUNIOR.matcher(t).find()) {
            return Seniority.JUNIOR;
        }
        return null;
    }

    /** A candidate's level: from their current title when it says, otherwise from years of experience. */
    public static Seniority ofCandidate(String currentTitle, BigDecimal years) {
        Seniority byTitle = fromTitle(currentTitle);
        if (byTitle != null) {
            return byTitle;
        }
        double y = years == null ? 0 : years.doubleValue();
        if (y < 2) {
            return Seniority.JUNIOR;
        }
        if (y < 5) {
            return Seniority.MID;
        }
        if (y < 9) {
            return Seniority.SENIOR;
        }
        return Seniority.LEAD;
    }

    public static DegreeLevel degreeLevel(String degree) {
        if (degree == null || degree.isBlank()) {
            return DegreeLevel.NONE;
        }
        String d = degree.toLowerCase(Locale.ROOT).replace(".", "");
        if (d.matches(".*\\b(phd|doctor|doctorate|dphil)\\b.*")) {
            return DegreeLevel.PHD;
        }
        if (d.matches(".*\\b(master|masters|mtech|me|ms|msc|mca|mba|ma|mcom|pgdm|post ?graduate)\\b.*")) {
            return DegreeLevel.MASTERS;
        }
        if (d.matches(".*\\b(bachelor|bachelors|btech|be|bs|bsc|bca|ba|bcom|bba|undergraduate|graduate|degree)\\b.*")) {
            return DegreeLevel.BACHELORS;
        }
        if (d.matches(".*\\b(diploma|polytechnic)\\b.*")) {
            return DegreeLevel.DIPLOMA;
        }
        return DegreeLevel.NONE;
    }
}
