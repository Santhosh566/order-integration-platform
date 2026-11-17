package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Result of the Correlated Standalone Aggregator EIP.
 * Consolidates both the payment captured event and the warehouse packed event
 * for a specific orderId. Serializable because the aggregator keeps it in PostgreSQL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FulfillmentSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    private String orderId;
    private boolean paymentCaptured;
    private boolean warehousePacked;
    private String paymentDetails;
    private String warehouseDetails;
    private String completedAt;
    private boolean readyForDispatch;

    public FulfillmentSummary() {
    }

    public FulfillmentSummary(String orderId) {
        this.orderId = orderId;
    }

    public void applyEvent(FulfillmentEvent event) {
        if ("PAYMENT_CAPTURED".equalsIgnoreCase(event.getEventType())) {
            this.paymentCaptured = true;
            this.paymentDetails = event.getDetails();
        } else if ("WAREHOUSE_PACKED".equalsIgnoreCase(event.getEventType())) {
            this.warehousePacked = true;
            this.warehouseDetails = event.getDetails();
        }
        if (this.paymentCaptured && this.warehousePacked) {
            this.readyForDispatch = true;
            this.completedAt = LocalDateTime.now().toString();
        }
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public boolean isPaymentCaptured() {
        return paymentCaptured;
    }

    public void setPaymentCaptured(boolean paymentCaptured) {
        this.paymentCaptured = paymentCaptured;
    }

    public boolean isWarehousePacked() {
        return warehousePacked;
    }

    public void setWarehousePacked(boolean warehousePacked) {
        this.warehousePacked = warehousePacked;
    }

    public String getPaymentDetails() {
        return paymentDetails;
    }

    public void setPaymentDetails(String paymentDetails) {
        this.paymentDetails = paymentDetails;
    }

    public String getWarehouseDetails() {
        return warehouseDetails;
    }

    public void setWarehouseDetails(String warehouseDetails) {
        this.warehouseDetails = warehouseDetails;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }

    public boolean isReadyForDispatch() {
        return readyForDispatch;
    }

    public void setReadyForDispatch(boolean readyForDispatch) {
        this.readyForDispatch = readyForDispatch;
    }

    @Override
    public String toString() {
        return "FulfillmentSummary{" +
                "orderId='" + orderId + '\'' +
                ", paymentCaptured=" + paymentCaptured +
                ", warehousePacked=" + warehousePacked +
                ", readyForDispatch=" + readyForDispatch +
                ", completedAt='" + completedAt + '\'' +
                '}';
    }
}
