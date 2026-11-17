package com.example.orders.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an order is in its life cycle, and which moves are legal from each status.
 */
public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVED,
    CONFIRMED,
    READY_FOR_DISPATCH,
    SHIPPED,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    // A delivery attempt failed; the goods are back at the hub, waiting for another attempt
    DELIVERY_FAILED,
    // Given up after failed deliveries: back in stock and refunded
    RETURNED,
    OUT_OF_STOCK,
    PAYMENT_FAILED,
    FULFILLMENT_FAILED,
    FRAUD_REJECTED,
    CANCELLED;

    private static final Set<OrderStatus> HOLDS_STOCK =
            EnumSet.of(INVENTORY_RESERVED, CONFIRMED, SHIPPED, READY_FOR_DISPATCH);

    private static final Set<OrderStatus> REVENUE =
            EnumSet.of(CONFIRMED, SHIPPED, READY_FOR_DISPATCH, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_FAILED, DELIVERED);

    private static final Set<OrderStatus> FINISHED_UNFULFILLED =
            EnumSet.of(OUT_OF_STOCK, PAYMENT_FAILED, FULFILLMENT_FAILED, FRAUD_REJECTED, RETURNED, CANCELLED);

    private Set<OrderStatus> nextStatuses() {
        return switch (this) {
            // FULFILLMENT_FAILED: recovery gave up on an order the pipeline never picked up
            case PENDING -> EnumSet.of(INVENTORY_RESERVED, OUT_OF_STOCK, FRAUD_REJECTED, FULFILLMENT_FAILED, CANCELLED);
            case INVENTORY_RESERVED -> EnumSet.of(CONFIRMED, PAYMENT_FAILED, CANCELLED);
            case CONFIRMED -> EnumSet.of(SHIPPED, FULFILLMENT_FAILED, CANCELLED);
            case SHIPPED -> EnumSet.of(READY_FOR_DISPATCH, FULFILLMENT_FAILED, CANCELLED);
            case READY_FOR_DISPATCH -> EnumSet.of(IN_TRANSIT, CANCELLED);
            case IN_TRANSIT -> EnumSet.of(OUT_FOR_DELIVERY, DELIVERY_FAILED);
            case OUT_FOR_DELIVERY -> EnumSet.of(DELIVERED, DELIVERY_FAILED);
            // Picked up again for another attempt, or given up and returned to stock
            case DELIVERY_FAILED -> EnumSet.of(IN_TRANSIT, RETURNED);
            // An operator replay from the dead letter queue starts a failed order again
            case PAYMENT_FAILED, FULFILLMENT_FAILED -> EnumSet.of(PENDING);
            case DELIVERED, RETURNED, OUT_OF_STOCK, FRAUD_REJECTED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus next) {
        return nextStatuses().contains(next);
    }

    /** True while units are reserved for the order and have not left the warehouse. */
    public boolean holdsStock() {
        return HOLDS_STOCK.contains(this);
    }

    /** True when the order counts as a sale: it was paid and has not been reversed. */
    public boolean isRevenue() {
        return REVENUE.contains(this);
    }

    public static Set<OrderStatus> revenueStatuses() {
        return REVENUE;
    }

    /** Orders that ended without being fulfilled; a coupon or delivery slot they held is free again. */
    public static Set<OrderStatus> finishedUnfulfilledStatuses() {
        return FINISHED_UNFULFILLED;
    }

    /** Goods can still be stopped: nothing has left the warehouse and the order has not ended. */
    public boolean isCancellable() {
        return canMoveTo(CANCELLED);
    }

    /** The order ended without being fulfilled, so hiding it loses no live work. */
    public boolean isFinishedUnfulfilled() {
        return FINISHED_UNFULFILLED.contains(this);
    }

    /** Waiting for a delivery user to take it. */
    public boolean isAwaitingDriver() {
        return this == READY_FOR_DISPATCH || this == DELIVERY_FAILED;
    }
}
