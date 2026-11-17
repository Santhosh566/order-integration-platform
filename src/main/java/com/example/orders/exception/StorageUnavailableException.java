package com.example.orders.exception;

/** The photo storage (MinIO) cannot be reached or refused the request. Answered with 503. */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
