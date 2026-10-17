package com.referboard.analytics.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public final class InsightDtos {

    private InsightDtos() {
    }

    @Schema(description = "Tracker cards by the furthest stage they reached")
    public record Funnel(int saved, int applied, int referred, int interview, int offer, int rejected) {
    }

    @Schema(description = "Applications that went out, and how many reached an interview. rate is null below minSample.")
    public record Group(int applications, int interviews, Double rate) {
    }

    public record Comparison(Group referred, Group cold, int minSample) {
    }

    @Schema(description = "Referral contacts by the furthest status they reached")
    public record Referrals(int added, int requested, int connected, int asked, int referred, int noResponse) {
    }

    public record Resumes(int analyses, Integer averageScore, Integer bestScore, int tailored, Double avgGain,
                          int prepPacks) {
    }

    @Schema(description = "One week (Monday to Sunday, India time)")
    public record Week(LocalDate weekStart, int saved, int applied, int referralAsks, int interviews, int analyses,
                       Integer averageScore) {
    }

    public record Insights(Funnel funnel, Comparison referredVsCold, Referrals referrals,
                           @Schema(description = "Median days from application to interview") Double medianDaysToInterview,
                           Resumes resumes, List<Week> weekly,
                           @Schema(description = "Plain-language statements the numbers support") List<String> highlights) {
    }

    public record AdminWeek(LocalDate weekStart, int signups, int activeUsers, int analyses, int referralAsks,
                            int prepPacks, int purchases, long revenuePaise) {
    }

    public record AdminMetrics(int users, int proNow, int everPaid,
                               @Schema(description = "Percent of users who ever bought Pro") Double paidConversionPct,
                               long revenuePaise, List<AdminWeek> weekly) {
    }
}
