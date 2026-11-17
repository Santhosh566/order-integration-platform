package com.example.orders.exception;

/**
 * Thrown when a request names something other than an order that does not exist, such as a
 * route or a dead letter. Answered with 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
