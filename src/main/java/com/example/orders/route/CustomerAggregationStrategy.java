package com.example.orders.route;

import com.example.orders.model.CustomerProfile;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

/**
 * EIP: Aggregation Strategy for Content Enricher
 * Merges the fetched CustomerProfile into the ongoing Order Exchange.
 */
public class CustomerAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        if (newExchange == null) {
            return oldExchange;
        }

        CustomerProfile profile = newExchange.getIn().getBody(CustomerProfile.class);
        if (profile != null) {
            oldExchange.setProperty("customerProfile", profile);
            oldExchange.getMessage().setHeader("customerEmail", profile.getEmail());
            oldExchange.getMessage().setHeader("customerPhone", profile.getPhone());
            oldExchange.getMessage().setHeader("customerName", profile.getFullName());
        }

        return oldExchange;
    }
}
