package com.example.orders.model;

import com.example.orders.security.Viewer;

/**
 * One live update as it travels between pods on the platform.events topic.
 *
 * It carries two ready-made views, so no pod has to load anything to pass it on: the staff view for
 * admins, and for an order event the customer's view (without fraud scores or our costs) for the
 * customer it belongs to. Events without a customer (dead letters, routes) are for staff only.
 *
 * @param name            SSE event name: ORDER_UPDATED, DLQ_UPDATED, DLQ_REPLAYED, ROUTE_UPDATED
 * @param key             Kafka key; an order's events share it, so they arrive in order
 * @param customerId      the customer who may see it, or null for staff only
 * @param staffPayload    JSON for admins
 * @param customerPayload JSON for that customer, or null
 * @param origin          the pod that published it, for logs and debugging
 */
public record LiveEvent(String name, String key, String customerId, String staffPayload,
                        String customerPayload, String origin) {

    /** What this viewer may receive, or null when the event is not for them. */
    public String payloadFor(Viewer viewer) {
        if (viewer.isAdmin()) {
            return staffPayload;
        }
        return customerPayload != null && viewer.userId().equals(customerId) ? customerPayload : null;
    }
}
