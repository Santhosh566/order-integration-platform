package com.example.orders.model;

import java.math.BigDecimal;

/**
 * Result returned after attempting to charge a customer order.
 */
public class PaymentResult {

    public enum Status {
        SUCCESS,
        DECLINED,
        GATEWAY_ERROR
    }

    private String orderId;
    private Status status;
    private String transactionId;
    private BigDecimal amount;
    private String reason;

    public PaymentResult() {}

    public PaymentResult(String orderId, Status status, String transactionId, BigDecimal amount, String reason) {
        this.orderId = orderId;
        this.status = status;
        this.transactionId = transactionId;
        this.amount = amount;
        this.reason = reason;
    }

    public static PaymentResult success(String orderId, String transactionId, BigDecimal amount) {
        return new PaymentResult(orderId, Status.SUCCESS, transactionId, amount, "Payment authorized successfully");
    }

    public static PaymentResult declined(String orderId, BigDecimal amount, String reason) {
        return new PaymentResult(orderId, Status.DECLINED, null, amount, reason);
    }

    public static PaymentResult error(String orderId, BigDecimal amount, String reason) {
        return new PaymentResult(orderId, Status.GATEWAY_ERROR, null, amount, reason);
    }

    public boolean isSuccess() {
        return Status.SUCCESS.equals(this.status);
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    @Override
    public String toString() {
        return "PaymentResult{" +
                "orderId='" + orderId + '\'' +
                ", status=" + status +
                ", transactionId='" + transactionId + '\'' +
                ", amount=" + amount +
                ", reason='" + reason + '\'' +
                '}';
    }
}
