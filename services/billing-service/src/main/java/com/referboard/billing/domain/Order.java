package com.referboard.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    public enum Status { CREATED, PAID, REFUNDED, EXPIRED }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private String plan;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private int days;

    @Column(nullable = false, updatable = false)
    private String receipt;

    @Column(nullable = false, updatable = false)
    private String razorpayOrderId;

    @Column(nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    private String razorpayPaymentId;
    private String method;
    private Instant proFrom;
    private Instant proUntil;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    private Instant paidAt;
    private Instant refundedAt;

    @Version
    private long version;

    public Order(UUID id, UUID userId, String plan, long amountPaise, String currency, int days, String receipt,
                 String razorpayOrderId) {
        this.id = id;
        this.userId = userId;
        this.plan = plan;
        this.amountPaise = amountPaise;
        this.currency = currency;
        this.days = days;
        this.receipt = receipt;
        this.razorpayOrderId = razorpayOrderId;
        this.status = Status.CREATED;
    }
}
