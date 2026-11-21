package com.example.orders.model;

import com.example.orders.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One line of an order. The price and tax are a snapshot taken when the order was placed,
 * so a later catalogue change never alters an order, its invoice or a refund.
 */
@Entity
@Table(name = "order_items")
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;

    private String sku;
    // Units ordered, as on the invoice; cancelledQuantity of them were cancelled before dispatch
    private int quantity;

    @Column(name = "cancelled_quantity", nullable = false)
    private int cancelledQuantity;

    // Unit price as charged; includes GST when orders.tax.prices-include-tax is true
    private BigDecimal price = Money.ZERO;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "hsn_code")
    private String hsnCode;

    // This line's share of the order's coupon discount, taken off its price (GST included)
    @Column(nullable = false)
    private BigDecimal discount = Money.ZERO;

    // Percent, e.g. 18.00
    @Column(name = "gst_rate", nullable = false)
    private BigDecimal gstRate = Money.ZERO;

    // The value tax is charged on
    @Column(name = "taxable_value", nullable = false)
    private BigDecimal taxableValue = Money.ZERO;

    @Column(name = "cgst_amount", nullable = false)
    private BigDecimal cgstAmount = Money.ZERO;

    @Column(name = "sgst_amount", nullable = false)
    private BigDecimal sgstAmount = Money.ZERO;

    @Column(name = "igst_amount", nullable = false)
    private BigDecimal igstAmount = Money.ZERO;

    // What the customer pays for this line: taxable value plus tax
    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal = Money.ZERO;

    // Set on a line added after the order was placed (OrderChangeService); null on the lines ordered
    @Column(name = "added_at")
    private java.time.LocalDateTime addedAt;

    public OrderItem() {}

    public OrderItem(String sku, int quantity, BigDecimal price) {
        this.sku = sku;
        this.quantity = quantity;
        this.price = Money.of(price);
        applyNoTax();
    }

    /** Quantity times unit price, less this line's discount: what the line costs. */
    public BigDecimal grossAmount() {
        return listAmount().subtract(discount);
    }

    /** Quantity times unit price, before any discount. */
    public BigDecimal listAmount() {
        return Money.of(price.multiply(BigDecimal.valueOf(quantity)));
    }

    /** Untaxed line, used until the catalogue and GST are applied. */
    public void applyNoTax() {
        taxableValue = grossAmount();
        cgstAmount = Money.ZERO;
        sgstAmount = Money.ZERO;
        igstAmount = Money.ZERO;
        lineTotal = taxableValue;
    }

    public BigDecimal taxAmount() {
        return cgstAmount.add(sgstAmount).add(igstAmount);
    }

    public Long getId() { return id; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public int getQuantity() { return quantity; }

    public int getCancelledQuantity() { return cancelledQuantity; }
    public void setCancelledQuantity(int cancelledQuantity) { this.cancelledQuantity = cancelledQuantity; }

    /** Units still going to the customer: ordered less cancelled. */
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public int getActiveQuantity() { return quantity - cancelledQuantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = Money.of(price); }

    public BigDecimal getDiscount() { return discount; }
    public void setDiscount(BigDecimal discount) { this.discount = Money.of(discount == null ? BigDecimal.ZERO : discount); }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }

    public BigDecimal getGstRate() { return gstRate; }
    public void setGstRate(BigDecimal gstRate) { this.gstRate = Money.of(gstRate); }

    public BigDecimal getTaxableValue() { return taxableValue; }
    public void setTaxableValue(BigDecimal taxableValue) { this.taxableValue = Money.of(taxableValue); }

    public BigDecimal getCgstAmount() { return cgstAmount; }
    public void setCgstAmount(BigDecimal cgstAmount) { this.cgstAmount = Money.of(cgstAmount); }

    public BigDecimal getSgstAmount() { return sgstAmount; }
    public void setSgstAmount(BigDecimal sgstAmount) { this.sgstAmount = Money.of(sgstAmount); }

    public BigDecimal getIgstAmount() { return igstAmount; }
    public void setIgstAmount(BigDecimal igstAmount) { this.igstAmount = Money.of(igstAmount); }

    public java.time.LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(java.time.LocalDateTime addedAt) { this.addedAt = addedAt; }

    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = Money.of(lineTotal); }
}
