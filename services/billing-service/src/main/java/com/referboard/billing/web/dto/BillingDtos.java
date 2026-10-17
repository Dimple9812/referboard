package com.referboard.billing.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record PlanView(String id, String name, long amountPaise, String currency, int days) {
    }

    public record PlansResponse(
            @Schema(description = "False when Razorpay isn't configured: show plans, but don't offer checkout")
            boolean available,
            @Schema(description = "Razorpay test keys: no real money moves") boolean testMode,
            List<PlanView> plans) {
    }

    public record CreateOrderRequest(@NotBlank @Size(max = 30) String plan) {
    }

    @Schema(description = "Everything Razorpay Checkout needs to open")
    public record CheckoutResponse(UUID orderId, String razorpayOrderId, String keyId, long amountPaise, String currency,
                                   String name, String description, boolean testMode) {
    }

    public record VerifyRequest(@NotBlank @Size(max = 64) String razorpayPaymentId,
                                @NotBlank @Size(max = 128) String razorpaySignature) {
    }

    public record OrderView(UUID id, String plan, String planName, long amountPaise, String currency, String status,
                            String method, String receipt, String razorpayPaymentId, Instant createdAt, Instant paidAt,
                            Instant proFrom, Instant proUntil, Instant refundedAt) {
    }

    public record BillingStatus(
            @Schema(description = "PRO while a paid pass is running, otherwise FREE") String plan,
            Instant proUntil,
            @Schema(description = "Whole days left, counting today") Integer daysLeft,
            @Schema(description = "Paid and refunded orders, newest first") List<OrderView> orders) {
    }
}
