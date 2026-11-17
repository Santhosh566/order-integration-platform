package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Units of one order line in a return.
 */
@Entity
@Table(name = "return_items")
public class ReturnItem {

    /** What the warehouse found: SELLABLE goes back on the shelf, DAMAGED does not. */
    public enum Condition { SELLABLE, DAMAGED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition")
    private Condition condition;

    protected ReturnItem() {}

    public ReturnItem(Long orderItemId, String sku, int quantity) {
        this.orderItemId = orderItemId;
        this.sku = sku;
        this.quantity = quantity;
    }

    public void inspected(Condition condition) {
        this.condition = condition;
    }

    public Long getOrderItemId() { return orderItemId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public Condition getCondition() { return condition; }
}
