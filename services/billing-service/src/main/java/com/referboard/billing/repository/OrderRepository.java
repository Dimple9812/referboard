package com.referboard.billing.repository;

import com.referboard.billing.domain.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    /** Row lock: the browser's verify call and Razorpay's webhook can arrive together for the same order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.razorpayOrderId = :razorpayOrderId")
    Optional<Order> lockByRazorpayOrderId(String razorpayOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.razorpayPaymentId = :paymentId")
    Optional<Order> lockByPaymentId(String paymentId);

    List<Order> findByUserIdAndStatusNotOrderByCreatedAtDesc(UUID userId, Order.Status status);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    @Modifying
    @Query("update Order o set o.status = com.referboard.billing.domain.Order.Status.EXPIRED "
            + "where o.status = com.referboard.billing.domain.Order.Status.CREATED and o.createdAt < :before")
    int expireOpenBefore(Instant before);
}
