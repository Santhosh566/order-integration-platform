package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A message to the customer about their order, sent by email and SMS.
 *
 * Saved in the same transaction as the order change it describes (an outbox), so a message is
 * never lost after the change is committed, and never sent for a change that rolled back.
 * CustomerNotificationService sends it afterwards, retrying while the mail server is down.
 *
 *   PENDING → SENDING (claimed by one pod) → SENT
 *                 └─ failed → PENDING again after a back-off, or FAILED after the last attempt
 */
@Entity
@Table(name = "customer_notifications")
public class CustomerNotification {

    public enum Status { PENDING, SENDING, SENT, FAILED }

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    // Empty for staff messages, which have recipientEmail instead
    @Column(name = "order_id")
    private String orderId;

    // Empty for staff messages, which have recipientEmail instead
    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "subject", nullable = false)
    private String subject;

    // Removed once sent when it holds a secret, such as the delivery code
    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Column(name = "secret", nullable = false)
    private boolean secret;

    @Column(name = "status", nullable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    // When a pod took it; one that stopped mid-send leaves it SENDING, and it is retried after a while
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    // Set for messages to staff, such as low-stock alerts; they have no order or customer
    @Column(name = "recipient_email")
    private String recipientEmail;

    protected CustomerNotification() {}

    public CustomerNotification(String orderId, String customerId, String subject, String text) {
        this.id = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.customerId = customerId;
        this.subject = subject;
        this.text = text;
        this.status = Status.PENDING;
        this.createdAt = LocalDateTime.now();
        this.nextAttemptAt = createdAt;
    }

    /** A message to a fixed address rather than an order's customer. */
    public static CustomerNotification toStaff(String recipientEmail, String subject, String text) {
        CustomerNotification notification = new CustomerNotification(null, null, subject, text);
        notification.recipientEmail = recipientEmail;
        return notification;
    }

    /** A message whose text is removed from the database once it has been sent. */
    public static CustomerNotification secret(String orderId, String customerId, String subject, String text) {
        CustomerNotification notification = new CustomerNotification(orderId, customerId, subject, text);
        notification.secret = true;
        return notification;
    }

    public void claim(LocalDateTime now) {
        status = Status.SENDING;
        claimedAt = now;
    }

    public void sent(LocalDateTime now) {
        status = Status.SENT;
        sentAt = now;
        lastError = null;
        if (secret) {
            text = null;
        }
    }

    /**
     * @param retryAt when to try again, or null when this was the last attempt
     */
    public void failed(String error, LocalDateTime retryAt) {
        attempts++;
        lastError = error;
        if (retryAt == null) {
            status = Status.FAILED;
        } else {
            status = Status.PENDING;
            nextAttemptAt = retryAt;
        }
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getSubject() { return subject; }
    public String getText() { return text; }
    public boolean isSecret() { return secret; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public String getRecipientEmail() { return recipientEmail; }
}
