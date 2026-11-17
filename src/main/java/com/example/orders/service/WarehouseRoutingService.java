package com.example.orders.service;

import com.example.orders.money.IndianState;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.util.Map;
import com.example.orders.model.CustomerProfile;
import com.example.orders.model.Order;
import com.example.orders.model.ShippingAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enterprise Service supporting the Recipient List EIP (.recipientList()).
 * Computes dynamic physical fulfillment hub endpoints at runtime based on the
 * destination territory, customer tier, cargo priority, and order value.
 */
@Service
public class WarehouseRoutingService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseRoutingService.class);

    /** Regional warehouses: Gurugram (north), Bengaluru (south), Kolkata (east), Bhiwandi (west). */
    private static final Map<IndianState.Region, String> HUB_BY_REGION = Map.of(
            IndianState.Region.NORTH, "hubNorth",
            IndianState.Region.SOUTH, "hubSouth",
            IndianState.Region.EAST, "hubEast",
            IndianState.Region.WEST, "hubWest");

    private final CustomerService customerService;
    private final BigDecimal priorityAbove;

    public WarehouseRoutingService(CustomerService customerService,
                                   @Value("${orders.fulfillment.priority-above}") BigDecimal priorityAbove) {
        this.customerService = customerService;
        this.priorityAbove = priorityAbove;
    }

    /**
     * Computes a comma-separated list of dynamic Camel endpoint URIs.
     * Unlike static Multicast, the Recipient List evaluates recipient endpoints at runtime.
     *
     * @param order The canonical order
     * @return Comma-separated list of Camel endpoint URIs
     */
    public String resolveFulfillmentRecipients(Order order) {
        List<String> recipients = new ArrayList<>();

        if (order == null) {
            return "direct:hubCentral";
        }

        // Partner files keep their own order number as the reference
        String reference = order.getExternalReference() != null ? order.getExternalReference().toUpperCase() : "";
        String orderType = order.getOrderType() != null ? order.getOrderType().toUpperCase() : "";

        // 1. Regional Fulfillment Hub routing based on the destination state.
        // Orders from before addresses existed have none, so only Central handles them.
        ShippingAddress destination = order.getShippingAddress();
        if (destination != null && IndianState.isIndia(destination.getCountry())) {
            IndianState.find(destination.getState())
                    .map(state -> "direct:" + HUB_BY_REGION.get(state.region()))
                    .ifPresent(recipients::add);
        }

        // Always notify the Central distribution headquarters
        recipients.add("direct:hubCentral");

        // 2. High-Priority / White-Glove Hub for orders above orders.fulfillment.priority-above, or VIP accounts
        if (order.getTotal().compareTo(priorityAbove) > 0 || isVip(order.getCustomerId())) {
            recipients.add("direct:hubPriorityWhiteGlove");
        }

        // 3. Dropship Partner EDI notification if partner dropship order
        if (reference.startsWith("ORD-CSV") || orderType.contains("DROPSHIP") || orderType.contains("PARTNER")) {
            recipients.add("direct:hubPartnerDropship");
        }

        String recipientList = String.join(",", recipients);
        log.info("[RECIPIENT LIST EIP] Dynamically resolved {} recipient fulfillment hub(s) for Order {}: [{}]",
                recipients.size(), order.getOrderId(), recipientList);
        return recipientList;
    }

    private boolean isVip(String customerId) {
        CustomerProfile profile = customerService.getProfile(customerId);
        return profile != null && "VIP".equals(profile.getTier());
    }
}
