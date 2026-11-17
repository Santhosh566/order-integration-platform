package com.example.orders.controller;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.exception.OrderChangedException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.exception.ProductNotFoundException;
import com.example.orders.exception.StorageUnavailableException;
import com.example.orders.service.IfMatch;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The same JSON error replies as the Camel REST routes give, for the Spring controllers.
 */
@RestControllerAdvice(basePackages = "com.example.orders.controller")
public class ApiExceptionHandler {

    public static final String SAME_TIME = "Someone else changed this at the same time. Reload and try again.";

    @ExceptionHandler(InvalidOrderException.class)
    public ResponseEntity<Map<String, String>> badRequest(InvalidOrderException e) {
        return reply(HttpStatus.BAD_REQUEST, "REJECTED", e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(OrderNotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Map<String, String>> storageDown(StorageUnavailableException e) {
        return reply(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return reply(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", "The file is too large; uploads may be at most 30 MB (pictures 5 MB)");
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> missing(NotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, String>> productNotFound(ProductNotFoundException e) {
        return reply(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    // The order changed after the person opened it: they reload and decide again
    @ExceptionHandler(OrderChangedException.class)
    public ResponseEntity<Map<String, Object>> changed(OrderChangedException e) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .eTag(IfMatch.etag(e.getCurrentVersion()))
                .body(changedBody(e));
    }

    /** The reply for an order that changed, shared with the Camel routes. */
    public static Map<String, Object> changedBody(OrderChangedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "CHANGED");
        body.put("reason", e.getMessage());
        body.put("orderId", e.getOrderId());
        body.put("orderStatus", e.getStatus());
        body.put("version", e.getCurrentVersion());
        return body;
    }

    // Two saves of the same row at the same moment; the second is refused by its version
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> clashed(ObjectOptimisticLockingFailureException e) {
        return reply(HttpStatus.CONFLICT, "CONFLICT", SAME_TIME);
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<Map<String, String>> conflict(InvalidOrderStateException e) {
        return reply(HttpStatus.CONFLICT, "CONFLICT", e.getMessage());
    }

    // A database rule caught a race the checks could not, such as two drivers taking one order
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> raced(DataIntegrityViolationException e) {
        return reply(HttpStatus.CONFLICT, "CONFLICT", SAME_TIME);
    }

    private static ResponseEntity<Map<String, String>> reply(HttpStatus status, String code, String reason) {
        return ResponseEntity.status(status).body(Map.of("status", code, "reason", reason != null ? reason : code));
    }
}
