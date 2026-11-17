package com.example.orders.service;

import java.math.BigDecimal;
import com.example.orders.money.Money;
import org.springframework.beans.factory.annotation.Value;
import com.example.orders.model.FraudCheckResult;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for evaluating order risk, dynamically injected via Routing Slip EIP for High-Value orders.
 */
@Service("fraudDetectionService")
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final BigDecimal maxOrderValue;

    public FraudDetectionService(OrderRepository orderRepository, OrderStateService orderStateService,
                                 @Value("${orders.risk.max-order-value}") BigDecimal maxOrderValue) {
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.maxOrderValue = maxOrderValue;
    }

    /**
     * Account screening, run on every order: blacklisted customers are rejected whatever the amount.
     */
    @Transactional
    public FraudCheckResult screen(Order event) {
        return check(event, false);
    }

    /**
     * Full risk evaluation for high-value orders: the account screening plus the value rules.
     * Orders from customers starting with "FRAUD-" or above orders.risk.max-order-value are rejected.
     */
    @Transactional
    public FraudCheckResult evaluate(Order event) {
        return check(event, true);
    }

    private FraudCheckResult check(Order event, boolean fullReview) {
        // The Kafka message is a snapshot, so all decisions and writes use the stored order
        Order order = orderStateService.lock(event.getOrderId());
        if (order.getStatus() != OrderStatus.PENDING) {
            return FraudCheckResult.reject(order.getOrderId(), 0, "Order is already " + order.getStatus());
        }

        log.info("[ROUTING SLIP: FRAUD EVALUATION] Analyzing risk profile for Order: {} (Customer: {}, Total: {} {})",
                order.getOrderId(), order.getCustomerId(), order.getTotal(), order.getCurrency());

        if (order.getCustomerId() != null && order.getCustomerId().startsWith("FRAUD-")) {
            log.warn("[FRAUD DETECTED] High risk blacklisted account flagged for Order: {}", order.getOrderId());
            order.setFraudScore(0.96);
            order.setFraudStatus("REJECTED");
            orderStateService.moveTo(order, OrderStatus.FRAUD_REJECTED);
            return FraudCheckResult.reject(order.getOrderId(), 0.96, "Customer flagged on security risk watch-list");
        }

        if (!fullReview) {
            return FraudCheckResult.pass(order.getOrderId(), 0);
        }

        if (order.getTotal().compareTo(maxOrderValue) > 0) {
            log.warn("[FRAUD DETECTED] Order value {} is above the limit {} for Order: {}", order.getTotal(), maxOrderValue, order.getOrderId());
            order.setFraudScore(0.88);
            order.setFraudStatus("REJECTED");
            orderStateService.moveTo(order, OrderStatus.FRAUD_REJECTED);
            return FraudCheckResult.reject(order.getOrderId(), 0.88,
                    "Order total exceeds the risk limit of " + Money.format(maxOrderValue, order.getCurrency()));
        }

        // Passed security check
        double lowScore = 0.08;
        log.info("[FRAUD PASSED] Order {} cleared security risk check (Score: {})", order.getOrderId(), lowScore);
        order.setFraudScore(lowScore);
        order.setFraudStatus("PASSED");
        orderRepository.save(order);
        return FraudCheckResult.pass(order.getOrderId(), lowScore);
    }
}
