package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Represents an asynchronous fulfillment signal emitted by an independent department
 * (e.g., PAYMENT_CAPTURED from Payment Gateway Webhook or WAREHOUSE_PACKED from Warehouse Scanner).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FulfillmentEvent {

    private String orderId;
    private String eventType; // PAYMENT_CAPTURED, WAREHOUSE_PACKED
    private String actor;     // e.g., "STRIPE_WEBHOOK", "WAREHOUSE_STATION_4"
    private String details;   // e.g., "Payment captured via txn_98374", "Packed in Box #B-12"
    private String timestamp;

    public FulfillmentEvent() {
        this.timestamp = LocalDateTime.now().toString();
    }

    public FulfillmentEvent(String orderId, String eventType, String actor, String details) {
        this.orderId = orderId;
        this.eventType = eventType;
        this.actor = actor;
        this.details = details;
        this.timestamp = LocalDateTime.now().toString();
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "FulfillmentEvent{" +
                "orderId='" + orderId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", actor='" + actor + '\'' +
                ", details='" + details + '\'' +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
