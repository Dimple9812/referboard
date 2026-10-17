package com.referboard.billing.web;

import com.referboard.billing.service.PaymentService;
import com.referboard.billing.service.WebhookService;
import com.referboard.billing.web.dto.BillingDtos.BillingStatus;
import com.referboard.billing.web.dto.BillingDtos.CheckoutResponse;
import com.referboard.billing.web.dto.BillingDtos.CreateOrderRequest;
import com.referboard.billing.web.dto.BillingDtos.PlansResponse;
import com.referboard.billing.web.dto.BillingDtos.VerifyRequest;
import com.referboard.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing", description = "Pro passes paid with Razorpay")
public class BillingController {

    private final PaymentService payments;
    private final WebhookService webhooks;

    public BillingController(PaymentService payments, WebhookService webhooks) {
        this.payments = payments;
        this.webhooks = webhooks;
    }

    @GetMapping("/plans")
    @Operation(summary = "Plans and prices (public)")
    public PlansResponse plans() {
        return payments.plans();
    }

    @GetMapping("/me")
    @Operation(summary = "My plan, when Pro ends, and my payments")
    public BillingStatus me() {
        return payments.status(CurrentUser.id());
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a payment: creates a Razorpay order for Checkout")
    public CheckoutResponse createOrder(@Valid @RequestBody CreateOrderRequest req) {
        return payments.createOrder(CurrentUser.id(), req.plan());
    }

    @PostMapping("/orders/{id}/verify")
    @Operation(summary = "Confirm a payment with the signed result Razorpay Checkout returned")
    public BillingStatus verify(@PathVariable UUID id, @Valid @RequestBody VerifyRequest req) {
        return payments.verify(CurrentUser.id(), id, req.razorpayPaymentId(), req.razorpaySignature());
    }

    @PostMapping("/webhooks/razorpay")
    @Operation(summary = "Razorpay webhook (authorised by X-Razorpay-Signature, not a user token)")
    public Map<String, String> webhook(@RequestBody byte[] body,
                                       @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        return Map.of("result", webhooks.handle(body, signature, eventId).name());
    }
}
