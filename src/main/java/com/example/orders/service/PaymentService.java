package com.example.orders.service;

import com.example.orders.exception.PaymentGatewayException;
import com.example.orders.model.Order;
import com.example.orders.model.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service simulating integration with an external payment gateway.
 *
 * Demonstrates:
 *   - Success scenario (regular customers)
 *   - Declined scenario (customerId containing "FAIL")
 *   - Gateway outage / timeout scenario (customerId containing "DOWN") -> triggers Circuit Breaker
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public PaymentResult processPayment(Order order) {
        log.info("[PAYMENT GATEWAY] Processing charge for Order {} (Customer: {}, Amount: {} {})",
                order.getOrderId(), order.getCustomerId(), order.getTotal(), order.getCurrency());

        String customerId = order.getCustomerId() != null ? order.getCustomerId().toUpperCase() : "";

        // Simulated Outage: triggers Circuit Breaker
        if (customerId.contains("DOWN")) {
            log.error("[PAYMENT GATEWAY] Outage detected! Payment provider unavailable for Order {}", order.getOrderId());
            throw new PaymentGatewayException("Payment gateway connection timed out (HTTP 503)");
        }

        // Simulated Business Decline: insufficient balance or invalid card
        if (customerId.contains("FAIL")) {
            log.warn("[PAYMENT GATEWAY] Card declined for Order {} - Insufficient funds", order.getOrderId());
            return PaymentResult.declined(order.getOrderId(), order.getTotal(), "Insufficient funds / card declined");
        }

        // Happy path: Approved
        String txnId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[PAYMENT GATEWAY] Payment approved for Order {}! TransactionId: {}", order.getOrderId(), txnId);
        return PaymentResult.success(order.getOrderId(), txnId, order.getTotal());
    }
}
