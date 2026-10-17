package com.referboard.analytics.service;

import com.referboard.analytics.messaging.AnalyticsConsumers;
import com.referboard.analytics.web.dto.InsightDtos.AdminMetrics;
import com.referboard.analytics.web.dto.InsightDtos.AdminWeek;
import com.referboard.analytics.web.dto.InsightDtos.Comparison;
import com.referboard.analytics.web.dto.InsightDtos.Funnel;
import com.referboard.analytics.web.dto.InsightDtos.Group;
import com.referboard.analytics.web.dto.InsightDtos.Insights;
import com.referboard.analytics.web.dto.InsightDtos.Referrals;
import com.referboard.analytics.web.dto.InsightDtos.Resumes;
import com.referboard.analytics.web.dto.InsightDtos.Week;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the analytics model. Rates are only reported once a group has {@link #MIN_SAMPLE} applications: below that,
 * a percentage would read as a finding when it's noise. Plain-language highlights only state what the numbers show.
 */
@Service
public class InsightsService {

    static final int MIN_SAMPLE = 5;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InsightsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Insights forUser(UUID userId, int weeks) {
        Funnel funnel = jdbc.queryForObject("""
                select count(*) as saved,
                       count(*) filter (where coalesce(applied_at, referral_requested_at, referred_at, interview_at, offer_at) is not null) as applied,
                       count(*) filter (where referred_at is not null) as referred,
                       count(*) filter (where coalesce(interview_at, offer_at) is not null) as interview,
                       count(*) filter (where offer_at is not null) as offer,
                       count(*) filter (where rejected_at is not null) as rejected
                from applications where user_id = ?
                """, (rs, i) -> new Funnel(rs.getInt("saved"), rs.getInt("applied"), rs.getInt("referred"),
                rs.getInt("interview"), rs.getInt("offer"), rs.getInt("rejected")), userId);

        // Of applications that went out, how often each kind reached an interview.
        Map<Boolean, int[]> groups = new LinkedHashMap<>();
        jdbc.query("""
                select (referred_at is not null) as referred, count(*) as n,
                       count(*) filter (where coalesce(interview_at, offer_at) is not null) as interviews
                from applications
                where user_id = ? and coalesce(applied_at, referral_requested_at, referred_at, interview_at, offer_at) is not null
                group by 1
                """, (ResultSet rs) -> {
            groups.put(rs.getBoolean("referred"), new int[]{rs.getInt("n"), rs.getInt("interviews")});
        }, userId);
        Comparison comparison = new Comparison(group(groups.get(true)), group(groups.get(false)), MIN_SAMPLE);

        Referrals referrals = jdbc.queryForObject("""
                select count(*) as added,
                       count(*) filter (where coalesce(request_sent_at, connected_at, asked_at, referred_at) is not null) as requested,
                       count(*) filter (where coalesce(connected_at, asked_at, referred_at) is not null) as connected,
                       count(*) filter (where coalesce(asked_at, referred_at) is not null) as asked,
                       count(*) filter (where referred_at is not null) as referred,
                       count(*) filter (where status = 'NO_RESPONSE') as no_response
                from contacts where user_id = ?
                """, (rs, i) -> new Referrals(rs.getInt("added"), rs.getInt("requested"), rs.getInt("connected"),
                rs.getInt("asked"), rs.getInt("referred"), rs.getInt("no_response")), userId);

        Double medianDays = jdbc.queryForObject("""
                select percentile_cont(0.5) within group (order by extract(epoch from (coalesce(interview_at, offer_at)
                       - coalesce(applied_at, referral_requested_at, referred_at))) / 86400.0)
                from applications
                where user_id = ? and coalesce(interview_at, offer_at) is not null
                  and coalesce(applied_at, referral_requested_at, referred_at) is not null
                """, Double.class, userId);

        Resumes resumes = jdbc.queryForObject("""
                select count(*) filter (where kind = 'ANALYSIS') as analyses,
                       round(avg(score) filter (where kind = 'ANALYSIS')) as avg_score,
                       max(score) filter (where kind = 'ANALYSIS') as best_score,
                       count(*) filter (where kind = 'TAILORED_RESUME') as tailored,
                       round(avg(delta) filter (where kind = 'TAILORED_RESUME'), 1) as avg_gain,
                       count(*) filter (where kind = 'PREP_PACK') as preps
                from activity where user_id = ?
                """, (rs, i) -> new Resumes(rs.getInt("analyses"), nullableInt(rs, "avg_score"),
                nullableInt(rs, "best_score"), rs.getInt("tailored"), nullableDouble(rs, "avg_gain"), rs.getInt("preps")),
                userId);

        List<Week> weekly = weekly(userId, weeks);
        return new Insights(funnel, comparison, referrals, medianDays == null ? null : Math.round(medianDays * 10) / 10.0,
                resumes, weekly, highlights(funnel, comparison, referrals, medianDays, resumes));
    }

    private List<Week> weekly(UUID userId, int weeks) {
        LocalDate thisWeek = LocalDate.now(clock.withZone(AnalyticsConsumers.INDIA))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = thisWeek.minusWeeks(weeks - 1L);
        Map<LocalDate, Week> byWeek = new LinkedHashMap<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate w = from.plusWeeks(i);
            byWeek.put(w, new Week(w, 0, 0, 0, 0, 0, null));
        }
        jdbc.query("""
                select date_trunc('week', day)::date as week,
                       count(*) filter (where kind = 'JOB_SAVED') as saved,
                       count(*) filter (where kind = 'APPLIED') as applied,
                       count(*) filter (where kind = 'REFERRAL_ASKED') as asked,
                       count(*) filter (where kind = 'INTERVIEW') as interviews,
                       count(*) filter (where kind = 'ANALYSIS') as analyses,
                       round(avg(score) filter (where kind = 'ANALYSIS')) as avg_score
                from activity where user_id = ? and day >= ?
                group by 1
                """, (ResultSet rs) -> {
            LocalDate w = rs.getDate("week").toLocalDate();
            if (byWeek.containsKey(w)) {
                byWeek.put(w, new Week(w, rs.getInt("saved"), rs.getInt("applied"), rs.getInt("asked"),
                        rs.getInt("interviews"), rs.getInt("analyses"), nullableInt(rs, "avg_score")));
            }
        }, userId, Date.valueOf(from));
        return new ArrayList<>(byWeek.values());
    }

    /** Only statements the numbers support. */
    static List<String> highlights(Funnel f, Comparison c, Referrals r, Double medianDays, Resumes res) {
        List<String> out = new ArrayList<>();
        if (f.saved() == 0 && r.added() == 0 && res.analyses() == 0) {
            return out;
        }
        if (c.referred().rate() != null && c.cold().rate() != null) {
            out.add("Referred applications reached an interview " + c.referred().interviews() + " of "
                    + c.referred().applications() + " times (" + pct(c.referred().rate()) + "), cold ones "
                    + c.cold().interviews() + " of " + c.cold().applications() + " (" + pct(c.cold().rate()) + ").");
        } else if (f.applied() > 0) {
            out.add("Once " + MIN_SAMPLE + " referred and " + MIN_SAMPLE
                    + " cold applications have gone out, you'll see which gets more interviews for you.");
        }
        if (r.asked() > 0) {
            out.add(r.referred() + " of the " + r.asked() + " people you asked for a referral have referred you.");
        } else if (r.connected() > 0) {
            out.add("You're connected with " + r.connected() + (r.connected() == 1 ? " person" : " people")
                    + " but haven't asked for a referral yet.");
        }
        if (medianDays != null) {
            out.add("Your applications that led to an interview took a median of " + Math.round(medianDays)
                    + (Math.round(medianDays) == 1 ? " day." : " days."));
        }
        if (res.tailored() > 0 && res.avgGain() != null) {
            out.add("Tailored resumes raised your match score by " + res.avgGain() + " points on average.");
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminMetrics admin(int weeks) {
        LocalDate thisWeek = LocalDate.now(clock.withZone(AnalyticsConsumers.INDIA))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = thisWeek.minusWeeks(weeks - 1L);
        Timestamp now = Timestamp.from(clock.instant());
        int users = jdbc.queryForObject("select count(*) from user_facts where registered_at is not null", Integer.class);
        int pro = jdbc.queryForObject("select count(*) from user_facts where pro_until > ?", Integer.class, now);
        int everPaid = jdbc.queryForObject("select count(*) from user_facts where first_purchase_at is not null", Integer.class);
        Map<LocalDate, AdminWeek> byWeek = new LinkedHashMap<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate w = from.plusWeeks(i);
            byWeek.put(w, new AdminWeek(w, 0, 0, 0, 0, 0, 0, 0));
        }
        jdbc.query("""
                select date_trunc('week', day)::date as week,
                       count(*) filter (where kind = 'SIGNUP') as signups,
                       count(distinct user_id) filter (where kind <> 'SIGNUP') as active,
                       count(*) filter (where kind = 'ANALYSIS') as analyses,
                       count(*) filter (where kind = 'REFERRAL_ASKED') as asked,
                       count(*) filter (where kind = 'PREP_PACK') as preps,
                       count(*) filter (where kind = 'PURCHASE') as purchases,
                       coalesce(sum(amount_paise) filter (where kind in ('PURCHASE', 'REFUND')), 0) as revenue
                from activity where day >= ?
                group by 1
                """, (ResultSet rs) -> {
            LocalDate w = rs.getDate("week").toLocalDate();
            if (byWeek.containsKey(w)) {
                byWeek.put(w, new AdminWeek(w, rs.getInt("signups"), rs.getInt("active"), rs.getInt("analyses"),
                        rs.getInt("asked"), rs.getInt("preps"), rs.getInt("purchases"), rs.getLong("revenue")));
            }
        }, Date.valueOf(from));
        long revenue = jdbc.queryForObject("select coalesce(sum(amount_paise), 0) from activity where kind in ('PURCHASE', 'REFUND')",
                Long.class);
        return new AdminMetrics(users, pro, everPaid, users == 0 ? null : Math.round(1000.0 * everPaid / users) / 10.0,
                revenue, new ArrayList<>(byWeek.values()));
    }

    private static Group group(int[] g) {
        if (g == null) {
            return new Group(0, 0, null);
        }
        return new Group(g[0], g[1], g[0] >= MIN_SAMPLE ? Math.round(1000.0 * g[1] / g[0]) / 1000.0 : null);
    }

    private static String pct(double rate) {
        return Math.round(rate * 100) + "%";
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        Number n = (Number) rs.getObject(col);
        return n == null ? null : n.intValue();
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        Number n = (Number) rs.getObject(col);
        return n == null ? null : n.doubleValue();
    }
}
