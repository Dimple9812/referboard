package com.referboard.profile.parsing;

import com.referboard.skills.SkillTaxonomy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fallback parser used when no LLM is configured or the LLM call fails. It recognises common
 * section headings, contact details, date ranges, degrees and known skills. It only extracts — it never invents —
 * so its output is sparser than the LLM's; the UI asks the user to review it.
 */
@Component
public class HeuristicResumeParser {

    enum Section { HEADER, SUMMARY, EXPERIENCE, EDUCATION, SKILLS, PROJECTS, CERTIFICATIONS, OTHER }

    private static final Map<String, Section> HEADINGS = new LinkedHashMap<>();

    static {
        for (String h : List.of("experience", "work experience", "professional experience", "employment",
                "employment history", "work history", "career history", "internships", "internship experience")) {
            HEADINGS.put(h, Section.EXPERIENCE);
        }
        for (String h : List.of("education", "academics", "academic background", "educational qualifications",
                "qualifications")) {
            HEADINGS.put(h, Section.EDUCATION);
        }
        for (String h : List.of("skills", "technical skills", "key skills", "core skills", "core competencies",
                "technologies", "tech stack", "tools and technologies", "skills and tools")) {
            HEADINGS.put(h, Section.SKILLS);
        }
        for (String h : List.of("summary", "professional summary", "profile", "profile summary", "objective",
                "career objective", "about me", "about")) {
            HEADINGS.put(h, Section.SUMMARY);
        }
        for (String h : List.of("projects", "personal projects", "academic projects", "key projects")) {
            HEADINGS.put(h, Section.PROJECTS);
        }
        for (String h : List.of("certifications", "certificates", "licenses and certifications", "courses")) {
            HEADINGS.put(h, Section.CERTIFICATIONS);
        }
        for (String h : List.of("achievements", "awards", "hobbies", "interests", "languages", "publications",
                "extracurricular activities", "positions of responsibility", "references")) {
            HEADINGS.put(h, Section.OTHER);
        }
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d{1,3}[\\s-]?)?(?:\\d[\\s-]?){9,11}\\d(?!\\d)");
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://)?(?:www\\.)?(?:linkedin\\.com/in/|github\\.com/|gitlab\\.com/)[A-Za-z0-9_./-]+"
                    + "|\\bhttps?://[^\\s)]+");

    private static final String MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final String DATE = "(?:" + MONTH + "\\s*['’]?\\s*\\d{2,4}|\\d{1,2}\\s*[/-]\\s*\\d{4}|\\d{4})";
    private static final String END = "(?:" + DATE + "|present|current|now|till\\s+date|to\\s+date|ongoing)";
    private static final Pattern RANGE = Pattern.compile("(?i)(" + DATE + ")\\s*(?:-|–|—|to|till|until)\\s*(" + END + ")");

    private static final Pattern BULLET = Pattern.compile("^[•●◦▪■□➢➤►\\-*–·]\\s*");
    private static final Pattern TITLE_WORDS = Pattern.compile("(?i)\\b(engineer|developer|intern|manager|analyst|"
            + "lead|architect|consultant|designer|scientist|sde|associate|head|director|specialist|programmer|"
            + "administrator|officer|executive|founder|researcher|tester|qa|devops|sre|trainee)\\b");
    private static final Pattern DEGREE = Pattern.compile("(?i)\\b(B\\.?\\s?Tech|B\\.?\\s?E\\.?(?=\\s|,|$)|B\\.?\\s?Sc|"
            + "BCA|MCA|M\\.?\\s?Tech|M\\.?\\s?E\\.?(?=\\s|,|$)|M\\.?\\s?Sc|MBA|PGDM|Ph\\.?\\s?D|B\\.?\\s?Com|BBA|"
            + "Bachelor(?:'s)?(?: of [A-Za-z ]+)?|Master(?:'s)?(?: of [A-Za-z ]+)?|Diploma(?: in [A-Za-z ]+)?|"
            + "Higher Secondary|Senior Secondary|Class XII|Class X|HSC|SSC)\\b");
    /** "B.Tech in Computer Science" → field "Computer Science". */
    private static final Pattern FIELD = Pattern.compile("^\\s*(?:in|of|-|,)\\s+([A-Za-z&][A-Za-z& ]{1,60}?)\\s*(?=,|\\||\\(|\\d|$)");
    private static final Pattern INSTITUTION = Pattern.compile("(?i)\\b(University|Institute|College|IIT|NIT|IIIT|"
            + "BITS|School|Academy|Vidyalaya|Polytechnic)\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(19[7-9]\\d|20[0-4]\\d)\\b");
    private static final Pattern GRADE = Pattern.compile("(?i)(?:CGPA|GPA|CPI|SGPA)\\s*[:\\-]?\\s*(\\d{1,2}(?:\\.\\d{1,2})?"
            + "(?:\\s*/\\s*\\d{1,2})?)|(\\d{2}(?:\\.\\d{1,2})?\\s*%)");

    private final SkillTaxonomy taxonomy;

    public HeuristicResumeParser(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    public ParsedResume parse(String text) {
        Map<Section, List<String>> sections = split(text);
        List<String> header = sections.getOrDefault(Section.HEADER, List.of());

        List<ParsedResume.ParsedExperience> experiences = experiences(sections.getOrDefault(Section.EXPERIENCE, List.of()));
        String headline = experiences.isEmpty() ? null : experiences.getFirst().title();

        return new ParsedResume(
                name(header),
                first(EMAIL, text),
                phone(text),
                null,
                headline,
                joinOrNull(sections.get(Section.SUMMARY)),
                skills(sections.getOrDefault(Section.SKILLS, List.of()), text),
                experiences,
                educations(sections.getOrDefault(Section.EDUCATION, List.of())),
                sections.getOrDefault(Section.CERTIFICATIONS, List.of()).stream()
                        .map(HeuristicResumeParser::stripBullet).filter(s -> !s.isBlank()).toList(),
                links(text));
    }

    // ---------- sections ----------

    static Map<Section, List<String>> split(String text) {
        Map<Section, List<String>> out = new EnumMap<>(Section.class);
        Section current = Section.HEADER;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Section heading = heading(line);
            if (heading != null) {
                current = heading;
                continue;
            }
            out.computeIfAbsent(current, k -> new ArrayList<>()).add(line);
        }
        return out;
    }

    static Section heading(String line) {
        if (line.length() > 45) {
            return null;
        }
        String key = line.toLowerCase(Locale.ROOT).replaceAll("[^a-z& ]", " ").replace("&", "and")
                .replaceAll("\\s+", " ").trim();
        return HEADINGS.get(key);
    }

    // ---------- header ----------

    private static String name(List<String> header) {
        for (String line : header.subList(0, Math.min(4, header.size()))) {
            String candidate = line.split("[|•,]")[0].trim();
            int words = candidate.split("\\s+").length;
            if (words >= 2 && words <= 5 && candidate.length() <= 50 && candidate.matches("[\\p{L} .'-]+")) {
                return candidate;
            }
        }
        return null;
    }

    private static String phone(String text) {
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("\\D", "");
            if (digits.length() >= 10 && digits.length() <= 13) {
                return m.group().trim();
            }
        }
        return null;
    }

    private static List<String> links(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            out.add(m.group().replaceAll("[.,;]+$", ""));
        }
        return List.copyOf(out);
    }

    // ---------- skills ----------

    private List<String> skills(List<String> skillLines, String fullText) {
        Map<String, String> byCanonical = new LinkedHashMap<>();
        for (String line : skillLines) {
            String withoutLabel = line.contains(":") ? line.substring(line.indexOf(':') + 1) : line;
            for (String token : withoutLabel.split("[,|•;·/]|\\s{2,}")) {
                String t = stripBullet(token).trim();
                if (t.length() >= 1 && t.length() <= 40 && !t.matches(".*\\d{4}.*")) {
                    byCanonical.putIfAbsent(taxonomy.normalize(t), taxonomy.display(t));
                }
            }
        }
        for (String skill : taxonomy.extract(fullText)) {
            byCanonical.putIfAbsent(skill, taxonomy.display(skill));
        }
        byCanonical.remove(null);
        return List.copyOf(byCanonical.values());
    }

    // ---------- experience ----------

    List<ParsedResume.ParsedExperience> experiences(List<String> lines) {
        List<Integer> rangeLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!isBullet(lines.get(i)) && RANGE.matcher(lines.get(i)).find()) {
                rangeLines.add(i);
            }
        }
        // For each entry, the header may sit on the date line or on up to two short lines just above it.
        int[] headerStart = new int[rangeLines.size()];
        for (int k = 0; k < rangeLines.size(); k++) {
            int r = rangeLines.get(k);
            int floor = k == 0 ? 0 : rangeLines.get(k - 1) + 1;
            int h = r;
            while (h - 1 >= floor && r - (h - 1) <= 2 && isHeaderish(lines.get(h - 1))) {
                h--;
            }
            headerStart[k] = h;
        }

        List<ParsedResume.ParsedExperience> out = new ArrayList<>();
        for (int k = 0; k < rangeLines.size(); k++) {
            int r = rangeLines.get(k);
            String dateLine = lines.get(r);
            Matcher m = RANGE.matcher(dateLine);
            m.find();
            String start = yearMonth(m.group(1));
            String endRaw = m.group(2);
            boolean current = endRaw.matches("(?i)present|current|now|till\\s+date|to\\s+date|ongoing");
            String end = current ? null : yearMonth(endRaw);

            List<String> headerParts = new ArrayList<>(lines.subList(headerStart[k], r));
            String rest = cleanSeparators(dateLine.substring(0, m.start()) + " " + dateLine.substring(m.end()));
            if (!rest.isBlank()) {
                headerParts.add(rest);
            }
            String[] titleCompany = titleAndCompany(headerParts);

            int bulletsEnd = k + 1 < rangeLines.size() ? headerStart[k + 1] : lines.size();
            List<String> bullets = new ArrayList<>();
            String location = null;
            for (int i = r + 1; i < bulletsEnd; i++) {
                String line = lines.get(i);
                if (i == r + 1 && location == null && !isBullet(line) && line.length() <= 40
                        && line.matches("[\\p{L} .,()-]+") && !TITLE_WORDS.matcher(line).find()) {
                    location = line;
                    continue;
                }
                String b = stripBullet(line);
                if (!isBullet(line) && !bullets.isEmpty() && Character.isLowerCase(b.charAt(0))) {
                    // wrapped continuation of the previous bullet
                    bullets.set(bullets.size() - 1, bullets.getLast() + " " + b);
                } else if (!b.isBlank()) {
                    bullets.add(b);
                }
            }
            out.add(new ParsedResume.ParsedExperience(titleCompany[1], titleCompany[0], location, start, end,
                    current, null, bullets));
        }
        return out;
    }

    /** Returns {title, company}. */
    static String[] titleAndCompany(List<String> parts) {
        List<String> pieces = new ArrayList<>();
        for (String p : parts) {
            String s = cleanSeparators(p);
            int at = s.toLowerCase(Locale.ROOT).indexOf(" at ");
            if (at > 0) {
                pieces.add(s.substring(0, at).trim());
                pieces.add(s.substring(at + 4).trim());
            } else {
                for (String piece : s.split("\\s*(?:\\||–|—|\\s-\\s|,|@)\\s*")) {
                    if (!piece.isBlank()) {
                        pieces.add(piece.trim());
                    }
                }
            }
        }
        if (pieces.isEmpty()) {
            return new String[]{"Unknown title", "Unknown company"};
        }
        String title = pieces.stream().filter(p -> TITLE_WORDS.matcher(p).find()).findFirst().orElse(pieces.getFirst());
        String company = pieces.stream().filter(p -> !p.equals(title)).findFirst().orElse("Unknown company");
        return new String[]{title, company};
    }

    // ---------- education ----------

    List<ParsedResume.ParsedEducation> educations(List<String> lines) {
        List<ParsedResume.ParsedEducation> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripBullet(lines.get(i));
            if (!INSTITUTION.matcher(line).find()) {
                continue;
            }
            String neighbourhood = String.join(" ",
                    i > 0 ? lines.get(i - 1) : "", line, i + 1 < lines.size() ? lines.get(i + 1) : "");
            Matcher degree = DEGREE.matcher(neighbourhood);
            String degreeText = null;
            String field = null;
            if (degree.find()) {
                degreeText = degree.group().trim();
                Matcher f = FIELD.matcher(neighbourhood.substring(degree.end()));
                if (f.find()) {
                    field = f.group(1).trim();
                }
            }
            List<Integer> years = new ArrayList<>();
            Matcher y = YEAR.matcher(line + " " + (i + 1 < lines.size() ? lines.get(i + 1) : ""));
            while (y.find()) {
                years.add(Integer.parseInt(y.group()));
            }
            Matcher g = GRADE.matcher(neighbourhood);
            String grade = g.find() ? (g.group(1) != null ? g.group(1) : g.group(2)).replaceAll("\\s", "") : null;
            String institution = cleanSeparators(RANGE.matcher(line).replaceAll(" ").replaceAll(YEAR.pattern(), " "));
            if (degreeText != null) {
                institution = cleanSeparators(institution.replace(degreeText, " "));
                if (field != null) {
                    institution = cleanSeparators(institution.replaceFirst("(?i)\\b(?:in|of)\\s+" + Pattern.quote(field), " "));
                }
            }
            out.add(new ParsedResume.ParsedEducation(institution, degreeText, field,
                    years.size() >= 2 ? years.get(0) : null, years.isEmpty() ? null : years.getLast(), grade));
        }
        return out;
    }

    // ---------- helpers ----------

    static String yearMonth(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace("’", "'");
        Matcher numeric = Pattern.compile("^(\\d{1,2})\\s*[/-]\\s*(\\d{4})$").matcher(s);
        if (numeric.find()) {
            int month = Integer.parseInt(numeric.group(1));
            return month >= 1 && month <= 12 ? "%s-%02d".formatted(numeric.group(2), month) : numeric.group(2) + "-01";
        }
        Matcher named = Pattern.compile("^([a-z]{3})[a-z]*\\.?\\s*'?\\s*(\\d{2,4})$").matcher(s);
        if (named.find()) {
            int month = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                    .indexOf(named.group(1)) + 1;
            String year = named.group(2).length() == 2 ? "20" + named.group(2) : named.group(2);
            return month == 0 ? year + "-01" : "%s-%02d".formatted(year, month);
        }
        Matcher year = Pattern.compile("^(\\d{4})$").matcher(s);
        return year.find() ? year.group(1) + "-01" : null;
    }

    private static boolean isBullet(String line) {
        return BULLET.matcher(line).find();
    }

    private static boolean isHeaderish(String line) {
        return !isBullet(line) && line.length() <= 80 && !line.endsWith(".");
    }

    private static String stripBullet(String line) {
        return BULLET.matcher(line.strip()).replaceFirst("").strip();
    }

    private static String cleanSeparators(String s) {
        return s.replaceAll("[()]", " ").replaceAll("^[\\s|,–—@-]+|[\\s|,–—@-]+$", "").replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static String joinOrNull(List<String> lines) {
        return lines == null || lines.isEmpty() ? null : String.join(" ", lines);
    }
}
