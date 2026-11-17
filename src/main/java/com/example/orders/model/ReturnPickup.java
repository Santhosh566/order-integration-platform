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
 * One attempt by a delivery user to collect an approved return from the customer.
 *
 * ASSIGNED → ON_THE_WAY (the customer gets a code) → COLLECTED, or FAILED and back on the list.
 */
@Entity
@Table(name = "return_pickups")
public class ReturnPickup {

    public enum Status {
        ASSIGNED, ON_THE_WAY, COLLECTED, FAILED, REASSIGNED, CANCELLED;

        /** The attempt still holds the return: nobody else may take it. */
        public boolean isLive() {
            return this == ASSIGNED || this == ON_THE_WAY;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pickup_id")
    private Long id;

    @Column(name = "return_id", nullable = false)
    private String returnId;

    @Column(name = "delivery_user_id", nullable = false)
    private String deliveryUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ASSIGNED;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "collected_at")
    private LocalDateTime collectedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    // A 6-digit code is found from its hash in moments, so it never leaves the server
    @Column(name = "code_hash")
    @JsonIgnore
    private String codeHash;

    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts;

    /** What the driver earns for this attempt, fixed when it finishes; null while it is open or unpaid */
    @Column(name = "pay_amount")
    private java.math.BigDecimal payAmount;

    protected ReturnPickup() {}

    public ReturnPickup(String returnId, String deliveryUserId, int attemptNumber) {
        this.returnId = returnId;
        this.deliveryUserId = deliveryUserId;
        this.attemptNumber = attemptNumber;
    }

    public Long getId() { return id; }
    public String getReturnId() { return returnId; }
    public String getDeliveryUserId() { return deliveryUserId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttemptNumber() { return attemptNumber; }
    public LocalDateTime getAssignedAt() { return assignedAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime collectedAt) { this.collectedAt = collectedAt; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public int getCodeAttempts() { return codeAttempts; }
    public void setCodeAttempts(int codeAttempts) { this.codeAttempts = codeAttempts; }
    public java.math.BigDecimal getPayAmount() { return payAmount; }
    public void setPayAmount(java.math.BigDecimal payAmount) { this.payAmount = payAmount; }
}
