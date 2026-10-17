package com.referboard.billing.service;

import com.referboard.billing.config.BillingProperties;
import com.referboard.billing.domain.Order;
import com.referboard.billing.razorpay.RazorpayClient;
import com.referboard.billing.razorpay.RazorpaySignatures;
import com.referboard.billing.repository.Entitlements;
import com.referboard.billing.repository.OrderRepository;
import com.referboard.billing.web.dto.BillingDtos.BillingStatus;
import com.referboard.billing.web.dto.BillingDtos.CheckoutResponse;
import com.referboard.billing.web.dto.BillingDtos.OrderView;
import com.referboard.billing.web.dto.BillingDtos.PlanView;
import com.referboard.billing.web.dto.BillingDtos.PlansResponse;
import com.referboard.common.events.EventJson;
import com.referboard.common.events.EventPublisher;
import com.referboard.common.web.ApiException;
import com.referboard.events.ProStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pro passes. A pass is a one-time payment for a number of days (no auto-renewal, nothing to cancel). A paid order
 * extends Pro from the later of now and the current end, so buying early never loses days. Payment is confirmed by
 * whichever arrives first, the browser's signed checkout response or Razorpay's signed webhook; both go through
 * {@link #markPaid}, which is idempotent under a row lock.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int MAX_ORDERS_PER_HOUR = 10;

    private final BillingProperties props;
    private final RazorpayClient razorpay;
    private final OrderRepository orders;
    private final Entitlements entitlements;
    private final EventPublisher events;
    private final EventJson eventJson;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PaymentService(BillingProperties props, RazorpayClient razorpay, OrderRepository orders,
                          Entitlements entitlements, EventPublisher events, EventJson eventJson, TransactionTemplate tx,
                          Clock clock) {
        this.props = props;
        this.razorpay = razorpay;
        this.orders = orders;
        this.entitlements = entitlements;
        this.events = events;
        this.eventJson = eventJson;
        this.tx = tx;
        this.clock = clock;
    }

    public PlansResponse plans() {
        List<PlanView> plans = props.plans().entrySet().stream()
                .map(e -> new PlanView(e.getKey(), e.getValue().name(), e.getValue().amountPaise(), "INR", e.getValue().days()))
                .sorted(Comparator.comparingInt(PlanView::days)).toList();
        return new PlansResponse(props.razorpay().configured(), props.razorpay().testMode(), plans);
    }

    public CheckoutResponse createOrder(UUID userId, String planId) {
        BillingProperties.Plan plan = props.plans().get(planId);
        if (plan == null) {
            throw ApiException.badRequest("UNKNOWN_PLAN", "There's no plan called " + planId + ".");
        }
        if (!props.razorpay().configured()) {
            throw RazorpayClient.unavailable();
        }
        if (orders.countByUserIdAndCreatedAtAfter(userId, clock.instant().minus(1, ChronoUnit.HOURS)) >= MAX_ORDERS_PER_HOUR) {
            throw ApiException.tooManyRequests("TOO_MANY_ORDERS", "Too many payment attempts. Try again in an hour.");
        }
        String receipt = "rb_" + Long.toString(Math.abs(RANDOM.nextLong()), 36);
        // Razorpay first, outside a transaction: a failure there leaves nothing behind here.
        RazorpayClient.Order rzp = razorpay.createOrder(plan.amountPaise(), "INR", receipt,
                Map.of("user_id", userId.toString(), "plan", planId));
        Order order = new Order(UUID.randomUUID(), userId, planId, plan.amountPaise(), "INR", plan.days(), receipt, rzp.id());
        orders.save(order);
        return new CheckoutResponse(order.getId(), rzp.id(), props.razorpay().keyId(), plan.amountPaise(), "INR",
                props.businessName(), plan.name(), props.razorpay().testMode());
    }

    /** The browser's checkout result. The signature proves Razorpay produced it for this order. */
    public BillingStatus verify(UUID userId, UUID orderId, String paymentId, String signature) {
        Order order = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        if (!RazorpaySignatures.validPayment(order.getRazorpayOrderId(), paymentId, signature,
                props.razorpay().keySecret())) {
            log.warn("Bad checkout signature for order {} (user {})", orderId, userId);
            throw ApiException.badRequest("INVALID_SIGNATURE", "That payment confirmation isn't valid.");
        }
        if (order.getStatus() != Order.Status.PAID) {
            RazorpayClient.Payment p = razorpay.payment(paymentId);
            if (!order.getRazorpayOrderId().equals(p.orderId()) || p.amount() != order.getAmountPaise()) {
                throw ApiException.badRequest("PAYMENT_MISMATCH", "That payment is for a different order.");
            }
            if ("authorized".equals(p.status())) {
                p = razorpay.capture(paymentId, order.getAmountPaise(), order.getCurrency());
            }
            if (!p.capturedOrAuthorized()) {
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_NOT_COMPLETED",
                        "Razorpay says this payment didn't go through (" + p.status() + "). You haven't been charged.");
            }
            markPaid(order.getRazorpayOrderId(), paymentId, p.method());
        }
        return status(userId);
    }

    /** Idempotent: a second confirmation of the same payment changes nothing. Returns false if the order is unknown. */
    public boolean markPaid(String razorpayOrderId, String paymentId, String method) {
        Boolean applied = tx.execute(s -> {
            Order order = orders.lockByRazorpayOrderId(razorpayOrderId).orElse(null);
            if (order == null) {
                return false;
            }
            if (order.getStatus() == Order.Status.PAID || order.getStatus() == Order.Status.REFUNDED) {
                if (!paymentId.equals(order.getRazorpayPaymentId())) {
                    log.error("Order {} already settled by payment {}; ignoring second payment {} (refund it in Razorpay)",
                            order.getId(), order.getRazorpayPaymentId(), paymentId);
                }
                return true;
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);   // what Postgres stores, so events match
            Instant current = entitlements.lock(order.getUserId());
            Instant from = current != null && current.isAfter(now) ? current : now;
            Instant until = from.plus(Duration.ofDays(order.getDays()));
            entitlements.set(order.getUserId(), until);
            order.setStatus(Order.Status.PAID);   // also rescues an order we'd marked EXPIRED: the customer paid
            order.setRazorpayPaymentId(paymentId);
            order.setMethod(method);
            order.setPaidAt(now);
            order.setProFrom(from);
            order.setProUntil(until);
            events.publish(new ProStatusChangedEvent(eventJson.newMetadata("ProStatusChanged"), order.getUserId(),
                    "PURCHASE", order.getPlan(), until, order.getId(), order.getAmountPaise(), order.getCurrency(), now));
            log.info("Order {} paid ({}); Pro for user {} until {}", order.getId(), paymentId, order.getUserId(), until);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    /** A full refund takes back the days that order added (never before now). Partial refunds don't change access. */
    public boolean refund(String paymentId) {
        Boolean applied = tx.execute(s -> {
            Order order = orders.lockByPaymentId(paymentId).orElse(null);
            if (order == null || order.getStatus() != Order.Status.PAID) {
                return order != null;
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);   // what Postgres stores, so events match
            Instant current = entitlements.lock(order.getUserId());
            Duration paidFor = Duration.between(order.getProFrom(), order.getProUntil());
            Instant until = current == null ? now : current.minus(paidFor);
            if (until.isBefore(now)) {
                until = now;
            }
            entitlements.set(order.getUserId(), until);
            order.setStatus(Order.Status.REFUNDED);
            order.setRefundedAt(now);
            events.publish(new ProStatusChangedEvent(eventJson.newMetadata("ProStatusChanged"), order.getUserId(),
                    "REFUND", order.getPlan(), until, order.getId(), -order.getAmountPaise(), order.getCurrency(), now));
            log.info("Order {} refunded; Pro for user {} now until {}", order.getId(), order.getUserId(), until);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    @Transactional(readOnly = true)
    public BillingStatus status(UUID userId) {
        Instant now = clock.instant();
        Instant until = entitlements.proUntil(userId).filter(u -> u.isAfter(Instant.EPOCH)).orElse(null);
        boolean pro = until != null && until.isAfter(now);
        Integer daysLeft = pro ? (int) Math.ceil(Duration.between(now, until).toMinutes() / 1440.0) : null;
        List<OrderView> history = orders.findByUserIdAndStatusNotOrderByCreatedAtDesc(userId, Order.Status.CREATED).stream()
                .filter(o -> o.getStatus() != Order.Status.EXPIRED)
                .map(this::view).toList();
        return new BillingStatus(pro ? "PRO" : "FREE", pro ? until : null, daysLeft, history);
    }

    private OrderView view(Order o) {
        BillingProperties.Plan plan = props.plans().get(o.getPlan());
        return new OrderView(o.getId(), o.getPlan(), plan == null ? o.getPlan() : plan.name(), o.getAmountPaise(),
                o.getCurrency(), o.getStatus().name(), o.getMethod(), o.getReceipt(), o.getRazorpayPaymentId(),
                o.getCreatedAt(), o.getPaidAt(), o.getProFrom(), o.getProUntil(), o.getRefundedAt());
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional
    public void expireAbandonedOrders() {
        int n = orders.expireOpenBefore(clock.instant().minus(props.orderTtl()));
        if (n > 0) {
            log.info("Marked {} unpaid orders as expired", n);
        }
    }
}
