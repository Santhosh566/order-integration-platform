package com.example.orders.exception;

/**
 * Thrown when an order cannot move to the requested status.
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
