package com.example.orders.exception;

/**
 * Thrown when a request names a product that is not in the catalogue.
 */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String sku) {
        super("Product not found: " + sku);
    }
}
