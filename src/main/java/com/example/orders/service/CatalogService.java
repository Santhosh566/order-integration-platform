package com.example.orders.service;

import java.math.BigDecimal;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.InventoryRepository;
import org.apache.camel.Body;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices come from the catalogue. A price in the request, or in a partner's file, is ignored:
 * otherwise a caller could buy anything for ₹0.01.
 */
@Service("catalogService")
public class CatalogService {

    private final InventoryRepository inventoryRepository;
    private final CustomerService customerService;
    private final GstService gstService;

    private final com.example.orders.security.CurrentUser currentUser;
    private final CheckoutService checkout;
    private final com.example.orders.repository.CategoryRepository categories;

    public CatalogService(InventoryRepository inventoryRepository, CustomerService customerService, GstService gstService,
                          com.example.orders.security.CurrentUser currentUser, CheckoutService checkout,
                          com.example.orders.repository.CategoryRepository categories) {
        this.currentUser = currentUser;
        this.checkout = checkout;
        this.categories = categories;
        this.inventoryRepository = inventoryRepository;
        this.customerService = customerService;
        this.gstService = gstService;
    }

    @Transactional(readOnly = true)
    public void requireOrderable(Order order) {
        for (OrderItem item : order.getItems()) {
            product(item.getSku());
        }
    }

    /**
     * Fixes where the order goes, then sets every line to the catalogue price and GST rate,
     * and works out the tax. The delivery state is needed first: it decides CGST + SGST or IGST.
     * Runs inside the order's own transaction, so it opens none of its own.
     */
    public void applyPrices(@Body Order order) {
        price(order, true);
    }

    /**
     * Prices the order: catalogue prices, the coupon's discount shared across the lines, then GST on what
     * is actually paid. A clothing line's rate follows its price per piece after the discount.
     *
     * @param lock true when placing it (the coupon stays locked until the order is saved); false for a quote
     */
    public void price(Order order, boolean lock) {
        if (order.getShippingAddress() == null) {
            order.setShippingAddress(customerService.defaultAddress(order.getCustomerId())
                    .map(ShippingAddress::new)
                    .orElseThrow(() -> new InvalidOrderException("The customer has no default address")));
        }
        java.util.Map<String, Integer> units = new java.util.HashMap<>();
        order.getItems().forEach(i -> units.merge(i.getSku(), i.getQuantity(), Integer::sum));
        // A retail limit: customers' own orders only; staff and partner (CSV/XML) orders are not capped
        boolean customerOrder = currentUser.get().map(v -> !v.isAdmin()).orElse(false);
        for (OrderItem item : order.getItems()) {
            InventoryItem product = product(item.getSku());
            Integer max = product.getMaxPerOrder();
            if (customerOrder && max != null && units.get(item.getSku()) > max) {
                throw new InvalidOrderException("At most " + max + " of " + product.getProductName() + " per order");
            }
            item.setPrice(product.getUnitPrice());
            item.setProductName(product.getProductName());
            item.setHsnCode(product.getHsnCode());
            item.setGstRate(product.getGstRate());
        }
        checkout.applyCoupon(order, lock);
        for (OrderItem item : order.getItems()) {
            if (item.getDiscount().signum() > 0) {
                InventoryItem product = product(item.getSku());
                String rule = gstRuleOf(product.getCategoryId());
                if (rule != null) {
                    BigDecimal perPiece = item.grossAmount().divide(BigDecimal.valueOf(item.getQuantity()), 2, java.math.RoundingMode.HALF_UP);
                    item.setGstRate(com.example.orders.money.GstRules.rateFor(rule, perPiece));
                }
            }
        }
        gstService.apply(order);
    }

    /** The GST rule of a category, or of its main category; null when neither has one. */
    private String gstRuleOf(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        com.example.orders.model.Category category = categories.findById(categoryId).orElse(null);
        if (category == null) {
            return null;
        }
        if (category.getGstRule() != null || category.getParentId() == null) {
            return category.getGstRule();
        }
        return categories.findById(category.getParentId()).map(com.example.orders.model.Category::getGstRule).orElse(null);
    }

    private InventoryItem product(String sku) {
        InventoryItem product = inventoryRepository.findById(sku).orElse(null);
        if (product == null || product.getUnitPrice() == null) {
            throw new InvalidOrderException("Unknown product: " + sku);
        }
        if (!product.isActive()) {
            throw new InvalidOrderException("Product " + sku + " is no longer sold");
        }
        return product;
    }
}
