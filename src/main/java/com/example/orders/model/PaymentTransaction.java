package com.example.orders.model;

import com.example.orders.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One charge or refund. Rows are only ever added, so a replayed order keeps its full payment history.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    public enum Kind { CHARGE, REFUND }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String gateway;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected PaymentTransaction() {}

    public PaymentTransaction(String orderId, Kind kind, String gateway, String transactionId,
                              BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.kind = kind;
        this.gateway = gateway;
        this.transactionId = transactionId;
        this.amount = Money.of(amount);
        this.currency = currency;
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public Kind getKind() { return kind; }
    public String getGateway() { return gateway; }
    public String getTransactionId() { return transactionId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
