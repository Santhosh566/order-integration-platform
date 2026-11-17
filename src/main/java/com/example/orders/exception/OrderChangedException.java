package com.example.orders.exception;

import com.example.orders.model.Order;

import java.time.format.DateTimeFormatter;

/**
 * The person acted on an older copy of the order than the one stored: someone else (the customer,
 * another admin, a driver or the pipeline) changed it after they opened it. Answered with
 * 412 Precondition Failed, so they reload and decide again instead of acting on what they saw.
 * <p>
 * It is a kind of {@link InvalidOrderStateException}, so anywhere without its own handling still
 * answers 409 rather than 500.
 */
public class OrderChangedException extends InvalidOrderStateException {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm");

    private final String orderId;
    private final long expectedVersion;
    private final long currentVersion;
    private final String status;

    public OrderChangedException(Order order, long expectedVersion) {
        super(message(order));
        this.orderId = order.getOrderId();
        this.expectedVersion = expectedVersion;
        this.currentVersion = order.getVersion() == null ? 0 : order.getVersion();
        this.status = order.getStatus() == null ? null : order.getStatus().name();
    }

    private static String message(Order order) {
        String since = order.getStatusChangedAt() == null ? "" : " since " + TIME.format(order.getStatusChangedAt());
        return "Order " + order.getOrderId() + " was changed after you opened it: it is now "
                + order.getStatus() + since + ". Reload to see the change, then try again.";
    }

    public String getOrderId() { return orderId; }
    public long getExpectedVersion() { return expectedVersion; }
    public long getCurrentVersion() { return currentVersion; }
    public String getStatus() { return status; }
}
