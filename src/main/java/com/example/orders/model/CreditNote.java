package com.example.orders.model;

import com.example.orders.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The GST document for money given back on an invoice: which lines, and how much of their
 * taxable value and tax. Numbered CN/yy-yy/nnnnnn, consecutive within the financial year.
 */
@Entity
@Table(name = "credit_notes")
public class CreditNote {

    public enum Kind { RETURN, GOODWILL, CANCELLATION }

    @Id
    @Column(name = "credit_note_number")
    private String number;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "return_id")
    private String returnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private String currency;

    @Column(name = "taxable_value", nullable = false)
    private BigDecimal taxableValue = Money.ZERO;

    @Column(name = "cgst_amount", nullable = false)
    private BigDecimal cgstAmount = Money.ZERO;

    @Column(name = "sgst_amount", nullable = false)
    private BigDecimal sgstAmount = Money.ZERO;

    @Column(name = "igst_amount", nullable = false)
    private BigDecimal igstAmount = Money.ZERO;

    @Column(nullable = false)
    private BigDecimal total = Money.ZERO;

    private String reason;

    // The refund that paid it; null for a cancellation on cash on delivery not collected yet
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "issued_by", nullable = false)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "credit_note_number", nullable = false)
    private List<CreditNoteLine> lines = new ArrayList<>();

    protected CreditNote() {}

    public CreditNote(String number, Order order, String returnId, Kind kind, List<CreditNoteLine> lines,
                      String reason, String issuedBy) {
        this.number = number;
        this.orderId = order.getOrderId();
        this.returnId = returnId;
        this.kind = kind;
        this.currency = order.getCurrency();
        this.lines = new ArrayList<>(lines);
        this.reason = reason;
        this.issuedBy = issuedBy;
        this.issuedAt = LocalDateTime.now();
        for (CreditNoteLine line : lines) {
            taxableValue = taxableValue.add(line.getTaxableValue());
            cgstAmount = cgstAmount.add(line.getCgstAmount());
            sgstAmount = sgstAmount.add(line.getSgstAmount());
            igstAmount = igstAmount.add(line.getIgstAmount());
        }
        total = taxableValue.add(cgstAmount).add(sgstAmount).add(igstAmount);
    }

    public void paidBy(PaymentTransaction refund) {
        this.paymentId = refund.getId();
    }

    public String getNumber() { return number; }
    public String getOrderId() { return orderId; }
    public String getReturnId() { return returnId; }
    public Kind getKind() { return kind; }
    public String getCurrency() { return currency; }
    public BigDecimal getTaxableValue() { return taxableValue; }
    public BigDecimal getCgstAmount() { return cgstAmount; }
    public BigDecimal getSgstAmount() { return sgstAmount; }
    public BigDecimal getIgstAmount() { return igstAmount; }
    public BigDecimal getTotal() { return total; }
    public String getReason() { return reason; }
    public Long getPaymentId() { return paymentId; }
    public String getIssuedBy() { return issuedBy; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public List<CreditNoteLine> getLines() { return lines; }
}
