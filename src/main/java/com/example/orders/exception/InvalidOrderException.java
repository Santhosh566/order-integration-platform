package com.example.orders.exception;

/**
 * Thrown when a submitted order breaks a business rule and must not be stored.
 */
public class InvalidOrderException extends RuntimeException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
