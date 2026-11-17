package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Entity representing a poisoned or failed message captured by the Dead Letter Channel (DLQ).
 * Quarantines unrecoverable payloads for inspection and operator replay.
 */
@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "exception_class")
    private String exceptionClass;

    @Column(name = "redelivery_attempts")
    private Integer redeliveryAttempts;

    @Column(name = "failed_at", nullable = false)
    private LocalDateTime failedAt;

    @Column(name = "replayed", nullable = false)
    private boolean replayed;

    @Column(name = "replayed_at")
    private LocalDateTime replayedAt;

    @Column(name = "replayed_by")
    private String replayedBy;

    // Closed by an operator without a replay; the note says why
    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "dismissed_by")
    private String dismissedBy;

    @Column(name = "dismiss_note")
    private String dismissNote;

    public DeadLetterEvent() {}

    public DeadLetterEvent(String orderId, String topic, String payload, String errorMessage,
                           String exceptionClass, Integer redeliveryAttempts) {
        this.orderId = orderId;
        this.topic = topic != null ? topic : "order.created";
        this.payload = payload;
        this.errorMessage = errorMessage;
        this.exceptionClass = exceptionClass;
        this.redeliveryAttempts = redeliveryAttempts != null ? redeliveryAttempts : 0;
        this.failedAt = LocalDateTime.now();
        this.replayed = false;
    }

    /** Still waiting for an operator: neither replayed nor dismissed. */
    public boolean isOpen() {
        return !replayed && dismissedAt == null;
    }

    public void replayedBy(String operator) {
        this.replayed = true;
        this.replayedAt = LocalDateTime.now();
        this.replayedBy = operator;
    }

    public void dismiss(String operator, String note) {
        this.dismissedAt = LocalDateTime.now();
        this.dismissedBy = operator;
        this.dismissNote = note;
    }

    public String getReplayedBy() { return replayedBy; }
    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public String getDismissedBy() { return dismissedBy; }
    public String getDismissNote() { return dismissNote; }

    // ── Getters and Setters ────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public Integer getRedeliveryAttempts() {
        return redeliveryAttempts;
    }

    public void setRedeliveryAttempts(Integer redeliveryAttempts) {
        this.redeliveryAttempts = redeliveryAttempts;
    }

    public LocalDateTime getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(LocalDateTime failedAt) {
        this.failedAt = failedAt;
    }

    public boolean isReplayed() {
        return replayed;
    }

    public void setReplayed(boolean replayed) {
        this.replayed = replayed;
    }

    public LocalDateTime getReplayedAt() {
        return replayedAt;
    }

    public void setReplayedAt(LocalDateTime replayedAt) {
        this.replayedAt = replayedAt;
    }
}
