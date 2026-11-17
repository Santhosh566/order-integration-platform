package com.example.orders.route;

import com.example.orders.model.FulfillmentEvent;
import com.example.orders.model.FulfillmentSummary;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 4.1: Standalone Correlated Aggregator Strategy
 *
 * Joins asynchronous signals for the same orderId:
 *   Signal 1: PAYMENT_CAPTURED (from Finance / Stripe webhook)
 *   Signal 2: WAREHOUSE_PACKED (from Warehouse Scanner)
 *
 * Only when both signals are joined does the exchange mark completionSize(2),
 * releasing the order to the shipping dock!
 */
@Component
public class OrderFulfillmentAggregationStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentAggregationStrategy.class);

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        FulfillmentEvent newEvent = newExchange.getMessage().getBody(FulfillmentEvent.class);
        String orderId = newExchange.getMessage().getHeader("orderId", String.class);

        if (oldExchange == null) {
            // First signal arrived!
            FulfillmentSummary summary = new FulfillmentSummary(orderId);
            if (newEvent != null) {
                summary.applyEvent(newEvent);
            }
            log.info("[CORRELATED AGGREGATOR] First signal received for Order {}: EventType='{}'. Waiting for matching 2nd signal...",
                     orderId, newEvent != null ? newEvent.getEventType() : "UNKNOWN");
            newExchange.getMessage().setBody(summary);
            return newExchange;
        }

        // Second signal arrived!
        FulfillmentSummary existingSummary = oldExchange.getMessage().getBody(FulfillmentSummary.class);
        if (existingSummary != null && newEvent != null) {
            existingSummary.applyEvent(newEvent);
        }

        log.info("[CORRELATED AGGREGATOR] Second signal received for Order {}: EventType='{}'. Both conditions satisfied! Ready for dispatch.",
                 orderId, newEvent != null ? newEvent.getEventType() : "UNKNOWN");

        oldExchange.getMessage().setBody(existingSummary);
        return oldExchange;
    }
}
