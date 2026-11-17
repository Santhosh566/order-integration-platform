package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox pattern: one row per domain event that must be published to Kafka.
 * Written in the SAME @Transactional block as the Order so the two are always consistent.
 *
 * Lifecycle:
 *   PENDING  → OutboxRoute picks it up and sends to Kafka
 *   PUBLISHED → OutboxRoute marks it after Kafka confirms
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    // e.g. "ORDER_CREATED"
    @Column(name = "event_type", nullable = false)
    private String eventType;

    // JSON string sent as the Kafka message value
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    // PENDING or PUBLISHED
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    // When a pod took the row; crash recovery measures from here, not from createdAt
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    public OutboxEvent() {}

    // Jackson 3 handles java.time itself; dates go out as ISO-8601 text
    private static final tools.jackson.databind.json.JsonMapper MAPPER =
        tools.jackson.databind.json.JsonMapper.builder()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    /**
     * Factory: build a ready-to-save PENDING outbox row from an Order.
     * The payload carries the full order representation including items.
     */
    public static OutboxEvent from(Order order) {
        OutboxEvent e = new OutboxEvent();
        e.id          = UUID.randomUUID().toString();
        e.orderId     = order.getOrderId();
        e.eventType   = "ORDER_CREATED";
        e.status      = "PENDING";
        e.createdAt   = LocalDateTime.now();

        try {
            e.payload = MAPPER.writeValueAsString(order);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize order to JSON outbox payload", ex);
        }
        return e;
    }

    /**
     * A dead-lettered order message sent again. It goes through the outbox like a new order, so it
     * reaches Kafka only after the order is back at PENDING in the database: sent directly, the
     * pipeline could read the order before that change was committed and skip it.
     */
    public static OutboxEvent replayOf(String orderId, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.id        = UUID.randomUUID().toString();
        e.orderId   = orderId;
        e.eventType = "ORDER_REPLAYED";
        e.status    = "PENDING";
        e.createdAt = LocalDateTime.now();
        e.payload   = payload;
        return e;
    }

    // ── getters / setters ──────────────────────────────────────────────────────

    public String getId()                         { return id; }
    public String getOrderId()                    { return orderId; }
    public String getEventType()                  { return eventType; }
    public String getPayload()                    { return payload; }
    public String getStatus()                     { return status; }
    public void   setStatus(String status)        { this.status = status; }
    public LocalDateTime getCreatedAt()           { return createdAt; }
    public LocalDateTime getPublishedAt()         { return publishedAt; }
    public void   setPublishedAt(LocalDateTime t) { this.publishedAt = t; }
    public LocalDateTime getClaimedAt()           { return claimedAt; }
    public void   setClaimedAt(LocalDateTime t)   { this.claimedAt = t; }
}
