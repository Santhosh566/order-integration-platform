package com.example.orders.exception;

/**
 * Thrown when an external payment gateway encounters timeouts, HTTP 500/503 errors,
 * or connection outages to trigger Circuit Breaker fallback.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
