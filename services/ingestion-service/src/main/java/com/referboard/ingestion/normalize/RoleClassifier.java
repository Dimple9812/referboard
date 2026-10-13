package com.referboard.ingestion.normalize;

import java.util.Locale;
import java.util.regex.Pattern;

/** Title-based classification: is it a tech role, which seniority band, and a normalized title for de-dup. */
public final class RoleClassifier {

    public enum Seniority { INTERN, ENTRY, MID, SENIOR, LEAD, PRINCIPAL }

    private static final Pattern TECH = Pattern.compile("(?i)\\b(engineer(ing)?|developer|dev|sde|sdet|sre|devops|"
            + "programmer|architect|data (scientist|analyst|engineer)|analytics|machine learning|ml|ai|"
            + "qa|quality assurance|test(er|ing)?|automation|security|infosec|product manager|product designer|"
            + "designer|ux|ui|frontend|front[- ]end|backend|back[- ]end|full[- ]?stack|mobile|android|ios|"
            + "cloud|platform|infrastructure|database|dba|tech(nical)? lead|cto|scientist|researcher|"
            + "solutions? (engineer|architect)|it support|network)\\b");

    private static final Pattern NON_TECH = Pattern.compile("(?i)\\b(account executive|sales|business development|"
            + "marketing|recruit(er|ing)|talent acquisition|human resources|\\bhr\\b|people partner|finance|"
            + "accountant|legal|counsel|paralegal|customer success|customer support|office|executive assistant|"
            + "content writer|copywriter|social media|partnerships|procurement|operations associate)\\b");

    private static final Pattern ENGINEERING_WORD = Pattern.compile(
            "(?i)\\b(engineer|developer|sde|programmer|architect|scientist)\\b");

    private RoleClassifier() {
    }

    public static boolean isTechRole(String title) {
        if (title == null) {
            return false;
        }
        if (NON_TECH.matcher(title).find()) {
            // "Sales Engineer" and "Marketing Data Scientist" are still technical roles.
            return ENGINEERING_WORD.matcher(title).find();
        }
        return TECH.matcher(title).find();
    }

    public static Seniority seniority(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\b(intern|internship|trainee|apprentice)\\b.*")) {
            return Seniority.INTERN;
        }
        if (t.matches(".*\\b(principal|distinguished|director|head of|vp|vice president|cto)\\b.*")) {
            return Seniority.PRINCIPAL;
        }
        if (t.matches(".*\\b(staff|lead|manager|architect|sde ?(iv|4))\\b.*")) {
            return Seniority.LEAD;
        }
        if (t.matches(".*\\b(senior|sr\\.?|sde ?(iii|3)|engineer (iii|3)|level 3)\\b.*")) {
            return Seniority.SENIOR;
        }
        if (t.matches(".*\\b(junior|jr\\.?|graduate|new grad|entry[- ]level|fresher|associate|sde ?(i|1)|"
                + "engineer (i|1))\\b.*")) {
            return Seniority.ENTRY;
        }
        return Seniority.MID;
    }

    /**
     * Lower-case, expand abbreviations, drop punctuation and location/remote suffixes:
     * "Sr. Backend Engineer - Bengaluru (Remote)" → "senior backend engineer".
     */
    public static String normalizeTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\bsr\\.?(?=\\s)", "senior")
                .replaceAll("\\bjr\\.?(?=\\s)", "junior")
                .replaceAll("\\bengg\\.?\\b", "engineer")
                .replaceAll("\\bmgr\\.?\\b", "manager")
                .replace("&", " and ");
        t = t.replaceAll("\\((?:[^)]*\\b(remote|hybrid|onsite|on-site|india|bengaluru|bangalore|mumbai|pune|"
                + "hyderabad|gurugram|gurgaon|delhi|chennai|noida)\\b[^)]*)\\)", " ");
        t = t.replaceAll("\\s[-–|,]\\s*(remote|hybrid|india|bengaluru|bangalore|mumbai|pune|hyderabad|gurugram|"
                + "gurgaon|delhi|chennai|noida)\\b.*$", " ");
        t = t.replaceAll("[^a-z0-9+#. ]", " ").replaceAll("(?<![a-z0-9])\\.|\\.(?![a-z0-9])", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** "Razorpay Software Private Limited" and "Razorpay" must de-dup to the same company. */
    public static String normalizeCompany(String company) {
        String c = company == null ? "" : company.toLowerCase(Locale.ROOT).replace("&", " and ");
        c = c.replaceAll("[^a-z0-9 ]", " ");
        c = c.replaceAll("\\b(private|pvt|limited|ltd|inc|incorporated|llc|llp|corp|corporation|co|gmbh|plc|"
                + "technologies|technology|tech|software|solutions|services|systems|india|global|the)\\b", " ");
        c = c.replaceAll("\\s+", " ").trim();
        return c.isEmpty() ? company.toLowerCase(Locale.ROOT).trim() : c;
    }
}
