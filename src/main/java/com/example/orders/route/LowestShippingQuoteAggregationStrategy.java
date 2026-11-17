package com.example.orders.route;

import com.example.orders.model.ShippingQuote;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;

/**
 * Gather Aggregator for Scatter-Gather EIP:
 * Recombines quotes from FedEx, DHL, and UPS, selecting the lowest price carrier.
 */
public class LowestShippingQuoteAggregationStrategy implements AggregationStrategy {

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        if (oldExchange == null) {
            return newExchange;
        }

        ShippingQuote oldQuote = oldExchange.getIn().getBody(ShippingQuote.class);
        ShippingQuote newQuote = newExchange.getIn().getBody(ShippingQuote.class);

        if (oldQuote == null) {
            return newExchange;
        }
        if (newQuote == null) {
            return oldExchange;
        }

        // Compare costs: select the lowest price quote
        if (newQuote.getCost().compareTo(oldQuote.getCost()) < 0) {
            return newExchange;
        } else {
            return oldExchange;
        }
    }
}
