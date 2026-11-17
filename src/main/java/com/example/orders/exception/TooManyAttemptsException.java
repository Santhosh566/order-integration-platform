package com.example.orders.exception;

/**
 * Too many failed tries in a short time, such as gift card codes that do not exist. Answered with 429.
 */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(String message) {
        super(message);
    }
}
