package com.example.orders.model;

import com.example.orders.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One entry in a product's price history. It becomes the catalogue price at effectiveFrom:
 * at once when that is now or past, otherwise when the price scheduler reaches it.
 * Orders keep the price they were placed at, so a change never touches an existing order.
 */
@Entity
@Table(name = "product_prices")
public class ProductPrice {

    public enum State { SCHEDULED, APPLIED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    // Including GST when orders.tax.prices-include-tax is true
    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "gst_rate", nullable = false)
    private BigDecimal gstRate;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ProductPrice() {}

    public ProductPrice(String sku, BigDecimal unitPrice, BigDecimal gstRate, LocalDateTime effectiveFrom,
                        String reason, String createdBy) {
        this.sku = sku;
        this.unitPrice = Money.of(unitPrice);
        this.gstRate = gstRate;
        this.effectiveFrom = effectiveFrom;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public State getState() {
        return cancelledAt != null ? State.CANCELLED : appliedAt != null ? State.APPLIED : State.SCHEDULED;
    }

    public void applied(LocalDateTime when) { this.appliedAt = when; }
    public void cancelled(LocalDateTime when) { this.cancelledAt = when; }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getGstRate() { return gstRate; }
    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
