package com.example.orders.model;

import java.math.BigDecimal;

/**
 * Logistics quote returned by carrier integration endpoints (FedEx, DHL, UPS)
 * evaluated by the Scatter-Gather EIP.
 */
public class ShippingQuote {

    private String carrier;
    private BigDecimal cost;
    private int estimatedDays;
    private String serviceLevel;

    public ShippingQuote() {}

    public ShippingQuote(String carrier, BigDecimal cost, int estimatedDays, String serviceLevel) {
        this.carrier = carrier;
        this.cost = cost;
        this.estimatedDays = estimatedDays;
        this.serviceLevel = serviceLevel;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    public int getEstimatedDays() {
        return estimatedDays;
    }

    public void setEstimatedDays(int estimatedDays) {
        this.estimatedDays = estimatedDays;
    }

    public String getServiceLevel() {
        return serviceLevel;
    }

    public void setServiceLevel(String serviceLevel) {
        this.serviceLevel = serviceLevel;
    }

    @Override
    public String toString() {
        return "ShippingQuote{" +
                "carrier='" + carrier + '\'' +
                ", cost=" + cost +
                ", estimatedDays=" + estimatedDays +
                ", serviceLevel='" + serviceLevel + '\'' +
                '}';
    }
}
