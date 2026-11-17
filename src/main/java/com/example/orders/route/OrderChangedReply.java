package com.example.orders.route;

import com.example.orders.controller.ApiExceptionHandler;
import com.example.orders.exception.OrderChangedException;
import com.example.orders.service.IfMatch;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * The Camel REST routes' replies when an order changed under someone: the same JSON the Spring
 * controllers give (see {@link ApiExceptionHandler}).
 */
final class OrderChangedReply {

    private OrderChangedReply() {
    }

    /** 412: the person acted on an older copy of the order. */
    static final Processor CHANGED = exchange -> {
        OrderChangedException e = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, OrderChangedException.class);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 412);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setHeader("ETag", IfMatch.etag(e.getCurrentVersion()));
        exchange.getMessage().setBody(JsonMapper.shared().writeValueAsString(ApiExceptionHandler.changedBody(e)));
    };

    /** 409: two saves of the same order at the same moment. */
    static final Processor CLASHED = exchange -> {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 409);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        exchange.getMessage().setBody(JsonMapper.shared().writeValueAsString(
                Map.of("status", "CONFLICT", "reason", ApiExceptionHandler.SAME_TIME)));
    };

}
