package com.example.orders.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * The one-time code a customer gives the driver to confirm they received the order.
 */
@Component
public class DeliveryCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    public String next() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
