package com.example.orders.model;

/**
 * Published whenever an order is created or changes status.
 */
public record OrderStatusChanged(Order order) {
}
