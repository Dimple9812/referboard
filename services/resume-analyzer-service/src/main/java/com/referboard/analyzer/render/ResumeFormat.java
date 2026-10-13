package com.referboard.analyzer.render;

import com.referboard.analyzer.model.ResumeContent;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text helpers shared by the renderers. */
final class ResumeFormat {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private ResumeFormat() {
    }

    /** "2021-03" → "Mar 2021"; anything else is shown as written. */
    static String date(String ym) {
        if (ym == null || ym.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(ym.trim().substring(0, Math.min(7, ym.trim().length()))).format(MONTH);
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return ym.trim();
        }
    }

    static String dates(ResumeContent.Experience e) {
        String start = date(e.startDate());
        String end = e.current() ? "Present" : date(e.endDate());
        if (start == null && end == null) {
            return null;
        }
        return (start == null ? "" : start) + (end == null ? "" : " – " + end);
    }

    static String contactLine(ResumeContent c) {
        List<String> parts = new ArrayList<>();
        add(parts, c.email());
        add(parts, c.phone());
        add(parts, c.location());
        c.links().forEach(l -> add(parts, l));
        return String.join("  |  ", parts);
    }

    static String education(ResumeContent.Education ed) {
        List<String> parts = new ArrayList<>();
        String degree = ed.degree() == null ? null : ed.degree() + (ed.fieldOfStudy() == null ? "" : ", " + ed.fieldOfStudy());
        add(parts, degree == null ? ed.fieldOfStudy() : degree);
        add(parts, ed.institution());
        String years = ed.startYear() != null && ed.endYear() != null ? ed.startYear() + " – " + ed.endYear()
                : ed.endYear() != null ? String.valueOf(ed.endYear()) : null;
        add(parts, years);
        add(parts, ed.grade());
        return String.join("  |  ", parts);
    }

    static String roleLine(ResumeContent.Experience e) {
        List<String> parts = new ArrayList<>();
        add(parts, e.title());
        add(parts, e.company());
        return String.join(", ", parts) + (e.location() == null || e.location().isBlank() ? "" : " (" + e.location() + ")");
    }

    private static void add(List<String> parts, String s) {
        if (s != null && !s.isBlank()) {
            parts.add(s.trim());
        }
    }
}
