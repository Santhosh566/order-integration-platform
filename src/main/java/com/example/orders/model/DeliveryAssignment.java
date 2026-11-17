package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One attempt by a delivery user to take an order to its destination.
 */
@Entity
@Table(name = "delivery_assignments")
public class DeliveryAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "delivery_user_id", nullable = false)
    private String deliveryUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status = DeliveryStatus.ASSIGNED;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    // Hash of the one-time code the customer gives the driver; never the code itself.
    // Never sent out either: a 6-digit code is found from its hash in moments
    @Column(name = "delivery_code_hash")
    @JsonIgnore
    private String deliveryCodeHash;

    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts;

    /** What the driver earns for this attempt, fixed when it finishes; null while it is open or unpaid */
    @Column(name = "pay_amount")
    private java.math.BigDecimal payAmount;

    protected DeliveryAssignment() {}

    public DeliveryAssignment(String orderId, String deliveryUserId, int attemptNumber) {
        this.orderId = orderId;
        this.deliveryUserId = deliveryUserId;
        this.attemptNumber = attemptNumber;
    }

    public Long getId() { return id; }

    public String getOrderId() { return orderId; }

    public String getDeliveryUserId() { return deliveryUserId; }

    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }

    public int getAttemptNumber() { return attemptNumber; }

    public LocalDateTime getAssignedAt() { return assignedAt; }

    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(LocalDateTime pickedUpAt) { this.pickedUpAt = pickedUpAt; }

    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }

    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getDeliveryCodeHash() { return deliveryCodeHash; }
    public void setDeliveryCodeHash(String deliveryCodeHash) { this.deliveryCodeHash = deliveryCodeHash; }

    public int getCodeAttempts() { return codeAttempts; }
    public void setCodeAttempts(int codeAttempts) { this.codeAttempts = codeAttempts; }
    public java.math.BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(java.math.BigDecimal payAmount) { this.payAmount = payAmount; }
}
