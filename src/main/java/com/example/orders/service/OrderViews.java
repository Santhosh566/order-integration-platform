package com.example.orders.service;

import com.example.orders.model.Order;
import com.example.orders.security.CurrentUser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

/**
 * What an order looks like to whoever asked for it. The Order entity also travels inside the
 * pipeline (outbox, Kafka), where every field is needed, so staff-only fields are removed here,
 * where an order leaves the platform for a customer, instead of on the entity.
 */
@Component
public class OrderViews {

    /** Risk scoring, our carrier cost and internal routing are not the customer's business. */
    static final Set<String> STAFF_ONLY = Set.of("fraudScore", "fraudStatus", "shippingCost", "orderType", "paymentGateway");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Amounts stay exact: 4999.00, not a double
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final CurrentUser currentUser;

    public OrderViews(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /** The order as the signed-in user may see it: whole for an admin, without staff fields otherwise. */
    public Object forCurrentViewer(Order order) {
        if (order == null || currentUser.require().isAdmin()) {
            return order;
        }
        return customerView(order);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> customerView(Order order) {
        Map<String, Object> view = MAPPER.convertValue(order, Map.class);
        STAFF_ONLY.forEach(view::remove);
        return view;
    }
}
