package com.example.orders.model;

import com.example.orders.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * How much of one order line a credit note gives back.
 */
@Entity
@Table(name = "credit_note_lines")
public class CreditNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    // Units given back; 0 when only the price was lowered
    @Column(nullable = false)
    private int quantity;

    @Column(name = "taxable_value", nullable = false)
    private BigDecimal taxableValue;

    @Column(name = "cgst_amount", nullable = false)
    private BigDecimal cgstAmount;

    @Column(name = "sgst_amount", nullable = false)
    private BigDecimal sgstAmount;

    @Column(name = "igst_amount", nullable = false)
    private BigDecimal igstAmount;

    protected CreditNoteLine() {}

    public CreditNoteLine(Long orderItemId, int quantity, BigDecimal taxableValue, BigDecimal cgstAmount,
                          BigDecimal sgstAmount, BigDecimal igstAmount) {
        this.orderItemId = orderItemId;
        this.quantity = quantity;
        this.taxableValue = Money.of(taxableValue);
        this.cgstAmount = Money.of(cgstAmount);
        this.sgstAmount = Money.of(sgstAmount);
        this.igstAmount = Money.of(igstAmount);
    }

    public BigDecimal total() {
        return taxableValue.add(cgstAmount).add(sgstAmount).add(igstAmount);
    }

    public Long getOrderItemId() { return orderItemId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getTaxableValue() { return taxableValue; }
    public BigDecimal getCgstAmount() { return cgstAmount; }
    public BigDecimal getSgstAmount() { return sgstAmount; }
    public BigDecimal getIgstAmount() { return igstAmount; }
}
