package com.example.orders.exception;

/**
 * Thrown when a request names an order that does not exist.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
    }
}
