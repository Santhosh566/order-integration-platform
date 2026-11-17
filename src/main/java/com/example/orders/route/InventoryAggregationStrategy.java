package com.example.orders.route;

import com.example.orders.model.InventoryCheckSummary;
import com.example.orders.model.ItemCheckResult;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

/**
 * EIP: Aggregator Strategy
 * Combines individual item stock check results into an overall InventoryCheckSummary.
 *
 * In Camel's Splitter EIP, oldExchange on the first iteration may be the parent exchange
 * containing the original Order. We check for (oldBody instanceof InventoryCheckSummary)
 * to initialize the summary cleanly.
 */
public class InventoryAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        if (newExchange == null) {
            return oldExchange;
        }

        ItemCheckResult itemResult = newExchange.getIn().getBody(ItemCheckResult.class);
        String orderId = newExchange.getProperty("currentOrderId", String.class);

        if (oldExchange == null) {
            InventoryCheckSummary summary = new InventoryCheckSummary(orderId);
            if (itemResult != null) {
                summary.addItemResult(itemResult);
            }
            newExchange.getIn().setBody(summary);
            return newExchange;
        }

        Object oldBody = oldExchange.getIn().getBody();
        InventoryCheckSummary summary;
        if (oldBody instanceof InventoryCheckSummary s) {
            summary = s;
        } else {
            // First aggregation step where oldExchange is the original parent exchange
            summary = new InventoryCheckSummary(orderId);
            oldExchange.getIn().setBody(summary);
        }

        if (itemResult != null) {
            summary.addItemResult(itemResult);
        }

        return oldExchange;
    }
}
