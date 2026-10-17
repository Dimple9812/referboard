package com.referboard.analytics.web;

import com.referboard.analytics.service.InsightsService;
import com.referboard.analytics.web.dto.InsightDtos.AdminMetrics;
import com.referboard.analytics.web.dto.InsightDtos.Insights;
import com.referboard.common.security.CurrentUser;
import com.referboard.common.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights")
@Tag(name = "Insights")
public class InsightsController {

    private final InsightsService insights;

    public InsightsController(InsightsService insights) {
        this.insights = insights;
    }

    @GetMapping
    @Operation(summary = "My job search in numbers: funnel, referred vs cold, referrals, weekly activity")
    public Insights mine(@RequestParam(defaultValue = "12") int weeks) {
        return insights.forUser(CurrentUser.id(), Math.max(1, Math.min(weeks, 52)));
    }

    @GetMapping("/admin")
    @Operation(summary = "Platform metrics (ADMIN role)")
    public AdminMetrics admin(@RequestParam(defaultValue = "12") int weeks) {
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin) {
            throw ApiException.forbidden("ADMIN_ONLY", "Only admins can see platform metrics.");
        }
        return insights.admin(Math.max(1, Math.min(weeks, 52)));
    }
}
