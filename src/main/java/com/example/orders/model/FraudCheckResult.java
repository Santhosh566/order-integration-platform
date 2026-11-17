package com.example.orders.model;

/**
 * Result of dynamic fraud evaluation triggered via Routing Slip EIP.
 */
public class FraudCheckResult {

    private String orderId;
    private double riskScore;
    private boolean approved;
    private String reason;

    public FraudCheckResult() {}

    public FraudCheckResult(String orderId, double riskScore, boolean approved, String reason) {
        this.orderId = orderId;
        this.riskScore = riskScore;
        this.approved = approved;
        this.reason = reason;
    }

    public static FraudCheckResult pass(String orderId, double score) {
        return new FraudCheckResult(orderId, score, true, "Low risk assessment");
    }

    public static FraudCheckResult reject(String orderId, double score, String reason) {
        return new FraudCheckResult(orderId, score, false, reason);
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(double riskScore) {
        this.riskScore = riskScore;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
