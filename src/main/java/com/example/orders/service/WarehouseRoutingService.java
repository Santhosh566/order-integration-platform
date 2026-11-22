package com.example.orders.service;

import com.example.orders.model.CustomerProfile;
import com.example.orders.model.Order;
import com.example.orders.warehouse.WarehouseService;
import org.apache.camel.ExchangeProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Recipient List EIP (.recipientList()): who hears about an order once its carrier is booked, worked out per order.
 *
 * - The warehouse it ships from (direct:warehouseHub): the pick list goes to that warehouse, the one its stock was
 *   reserved in (WarehouseAllocator), wherever the customer is. Warehouses an admin opens later need no new route.
 * - Head office (direct:hubCentral), for every order.
 * - The white-glove desk, for orders above orders.fulfillment.priority-above or VIP customers.
 * - The dropship partner's EDI portal, for partner orders.
 */
@Service
public class WarehouseRoutingService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseRoutingService.class);

    private final CustomerService customerService;
    private final WarehouseService warehouses;
    private final BigDecimal priorityAbove;

    public WarehouseRoutingService(CustomerService customerService, WarehouseService warehouses,
                                   @Value("${orders.fulfillment.priority-above}") BigDecimal priorityAbove) {
        this.customerService = customerService;
        this.warehouses = warehouses;
        this.priorityAbove = priorityAbove;
    }

    /**
     * The comma-separated endpoints for this order. Unlike a static Multicast, the Recipient List evaluates them at
     * runtime, from the order itself.
     */
    public String resolveFulfillmentRecipients(Order order) {
        if (order == null) {
            return "direct:hubCentral";
        }
        List<String> recipients = new ArrayList<>();
        // 1. The warehouse that packs it
        recipients.add("direct:warehouseHub");
        // 2. Head office hears about every order
        recipients.add("direct:hubCentral");

        // 3. White-glove desk for high-value orders and VIP accounts
        if (order.getTotal() != null && order.getTotal().compareTo(priorityAbove) > 0 || isVip(order.getCustomerId())) {
            recipients.add("direct:hubPriorityWhiteGlove");
        }

        // 4. Dropship partner portal for partner orders (partner files keep their own order number as the reference)
        String reference = order.getExternalReference() != null ? order.getExternalReference().toUpperCase() : "";
        String orderType = order.getOrderType() != null ? order.getOrderType().toUpperCase() : "";
        if (reference.startsWith("ORD-CSV") || orderType.contains("DROPSHIP") || orderType.contains("PARTNER")) {
            recipients.add("direct:hubPartnerDropship");
        }

        String recipientList = String.join(",", recipients);
        log.info("[RECIPIENT LIST EIP] {} recipient(s) for order {}: [{}]", recipients.size(), order.getOrderId(), recipientList);
        return recipientList;
    }

    /**
     * The warehouse direct:warehouseHub sends the pick list to: the one the order's stock was reserved in (orders
     * from before warehouses were recorded: the default one).
     */
    public WarehouseService.Warehouse pickListWarehouse(@ExchangeProperty("currentOrder") Order order) {
        WarehouseService.Warehouse warehouse = warehouses.get(warehouses.of(order));
        log.info("[RECIPIENT LIST: WAREHOUSE {}] Pick list for order {} sent to {}, {}", warehouse.code(),
                order.getOrderId(), warehouse.name(), warehouse.address());
        return warehouse;
    }

    private boolean isVip(String customerId) {
        CustomerProfile profile = customerService.getProfile(customerId);
        return profile != null && "VIP".equals(profile.getTier());
    }
}
