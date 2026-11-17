package com.example.orders.model;

/**
 * How one delivery attempt ended, or how far it has got.
 */
public enum DeliveryStatus {
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    FAILED,
    REASSIGNED,
    // The customer cancelled before pickup
    CANCELLED;

    /** The attempt still holds the order: nobody else may take it. */
    public boolean isLive() {
        return this == ASSIGNED || this == PICKED_UP;
    }
}
