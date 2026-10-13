package com.referboard.skills;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Total experience is computed from the dated roles, never taken from the LLM: overlapping roles are merged so two
 * concurrent jobs don't double-count, and a current role runs until {@code today}.
 */
public final class ExperienceMath {

    private ExperienceMath() {
    }

    public record Span(LocalDate start, LocalDate endExclusive) {
    }

    public static BigDecimal totalYears(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans.stream()
                .filter(s -> s.start() != null && s.endExclusive() != null && s.endExclusive().isAfter(s.start()))
                .sorted(Comparator.comparing(Span::start))
                .toList());
        if (sorted.isEmpty()) {
            return null;
        }
        long months = 0;
        LocalDate curStart = sorted.getFirst().start();
        LocalDate curEnd = sorted.getFirst().endExclusive();
        for (Span s : sorted.subList(1, sorted.size())) {
            if (!s.start().isAfter(curEnd)) {
                if (s.endExclusive().isAfter(curEnd)) {
                    curEnd = s.endExclusive();
                }
            } else {
                months += ChronoUnit.MONTHS.between(curStart, curEnd);
                curStart = s.start();
                curEnd = s.endExclusive();
            }
        }
        months += ChronoUnit.MONTHS.between(curStart, curEnd);
        return BigDecimal.valueOf(months).divide(BigDecimal.valueOf(12), 1, RoundingMode.HALF_UP);
    }

    /** A role from {@code start} through {@code end} inclusive (end month counts); null end + current → today. */
    public static Span span(LocalDate start, LocalDate end, boolean current, LocalDate today) {
        if (start == null) {
            return new Span(null, null);
        }
        LocalDate last = current || end == null ? today.withDayOfMonth(1) : end;
        return new Span(start, last.plusMonths(1));
    }

    /** {@code "2023-04"} → 2023-04-01; tolerant of null/garbage (→ null). Future dates are rejected. */
    public static LocalDate parseYearMonth(String value, LocalDate today) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate d = YearMonth.parse(value.trim().substring(0, Math.min(7, value.trim().length()))).atDay(1);
            return d.isAfter(today) ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
