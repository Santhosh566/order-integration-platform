package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.ShippingAddress;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Business rules every order must pass, whichever channel it arrived on (REST, CSV or XML).
 * Identifiers end up in file names and HTML, so they are limited to safe characters.
 */
@Service("orderValidator")
public class OrderValidator {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()-]{5,30}");
    private static final Pattern PIN_CODE = Pattern.compile("[1-9][0-9]{5}");
    private static final int MAX_TEXT = 255;

    private final CustomerService customerService;
    private final CatalogService catalogService;

    public OrderValidator(CustomerService customerService, CatalogService catalogService) {
        this.customerService = customerService;
        this.catalogService = catalogService;
    }

    public void validate(Order order) {
        requireSafeId(order.getOrderId(), "orderId");
        // Callers send their reference as orderId, so errors name that field
        if (order.getExternalReference() != null) {
            requireSafeId(order.getExternalReference(), "orderId");
        }
        requireSafeId(order.getCustomerId(), "customerId");
        if (!customerService.isActiveCustomer(order.getCustomerId())) {
            throw new InvalidOrderException("customerId is not an active customer");
        }
        requireDestination(order);

        List<OrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            throw new InvalidOrderException("At least one item is required");
        }
        for (OrderItem item : items) {
            requireSafeId(item.getSku(), "sku");
            if (item.getQuantity() < 1) {
                throw new InvalidOrderException("Every item needs a quantity of at least 1");
            }
        }
        // Checked here so a partner file with one unknown product imports nothing
        catalogService.requireOrderable(order);
    }

    public void validateAll(List<Order> orders) {
        orders.forEach(this::validate);
    }

    private void requireDestination(Order order) {
        ShippingAddress address = order.getShippingAddress();
        if (address == null) {
            if (customerService.defaultAddress(order.getCustomerId()).isEmpty()) {
                throw new InvalidOrderException("shippingAddress is required: the customer has no default address");
            }
            return;
        }
        validateAddress(address);
    }

    /**
     * Everything a delivery needs: someone to hand it to, a phone to call, and where to go.
     */
    public static void validateAddress(ShippingAddress address) {
        if (address == null) {
            throw new InvalidOrderException("shippingAddress is required");
        }
        requireText(address.getRecipientName(), "shippingAddress.recipientName");
        requireText(address.getLine1(), "shippingAddress.line1");
        requireText(address.getCity(), "shippingAddress.city");
        requireText(address.getPostalCode(), "shippingAddress.postalCode");
        requireText(address.getCountry(), "shippingAddress.country");
        requireLength(address.getLine2(), "shippingAddress.line2");
        requireLength(address.getState(), "shippingAddress.state");
        // Deliveries are within India: GST needs the state, and couriers need a valid PIN code
        GstService.placeOfSupply(address);
        if (!PIN_CODE.matcher(address.getPostalCode().trim()).matches()) {
            throw new InvalidOrderException("shippingAddress.postalCode must be a 6-digit Indian PIN code");
        }
        if (address.getRecipientPhone() == null || !PHONE.matcher(address.getRecipientPhone()).matches()) {
            throw new InvalidOrderException("shippingAddress.recipientPhone must be a valid phone number");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidOrderException(field + " is required");
        }
        requireLength(value, field);
    }

    private static void requireLength(String value, String field) {
        if (value != null && value.length() > MAX_TEXT) {
            throw new InvalidOrderException(field + " must be at most " + MAX_TEXT + " characters");
        }
    }

    private static void requireSafeId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new InvalidOrderException(field + " must be 1-64 letters, digits, dots, dashes or underscores");
        }
    }
}
