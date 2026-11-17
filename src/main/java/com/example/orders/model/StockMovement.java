package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One change to a product's stock. Rows are only ever added, so the history of every number
 * on the stock screen can be read back: what changed, by how much, why, and who did it.
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    public enum Type {
        /** The balance when the product was created or the ledger started */
        OPENING,
        /** Goods arrived from a supplier */
        RECEIPT,
        /** A stock count found a different number; the reason says why */
        ADJUSTMENT,
        /** Damaged, lost or expired goods taken out of stock */
        WRITE_OFF,
        /** An order set units aside */
        RESERVE,
        /** An order gave its reserved units back (cancelled or failed) */
        RELEASE,
        /** Reserved units left the warehouse with the courier */
        SHIP,
        /** Goods came back from a failed delivery or a customer return */
        RETURN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sku;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false)
    private Type type;

    @Column(name = "available_change", nullable = false)
    private int availableChange;

    @Column(name = "reserved_change", nullable = false)
    private int reservedChange;

    @Column(name = "available_after", nullable = false)
    private int availableAfter;

    @Column(name = "reserved_after", nullable = false)
    private int reservedAfter;

    @Column(name = "order_id")
    private String orderId;

    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StockMovement() {}

    public StockMovement(InventoryItem after, Type type, int availableChange, int reservedChange,
                         String orderId, String reason, String createdBy) {
        this.sku = after.getSku();
        this.type = type;
        this.availableChange = availableChange;
        this.reservedChange = reservedChange;
        this.availableAfter = after.getAvailableQuantity();
        this.reservedAfter = after.getReservedQuantity();
        this.orderId = orderId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public Type getType() { return type; }
    public int getAvailableChange() { return availableChange; }
    public int getReservedChange() { return reservedChange; }
    public int getAvailableAfter() { return availableAfter; }
    public int getReservedAfter() { return reservedAfter; }
    public String getOrderId() { return orderId; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
