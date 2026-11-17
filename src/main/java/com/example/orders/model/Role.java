package com.example.orders.model;

/**
 * What a user is allowed to do on the platform.
 */
public enum Role {
    ADMIN,
    CUSTOMER,
    DELIVERY,
    // Another system calling in, such as the warehouse or a payment webhook
    SERVICE
}
