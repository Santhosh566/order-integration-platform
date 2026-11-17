package com.example.orders.model;

import com.example.orders.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * What a caller may send to place an order. Payment, invoice, carrier and fraud fields are
 * not here, so a request cannot set them; any other field in the JSON is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderRequest {

    // The caller's own reference. The server issues the real order id.
    private String orderId;
    private String customerId;
    private List<OrderItem> items;
    private ShippingAddress shippingAddress;
    private String couponCode;
    private Order.PaymentMethod paymentMethod;
    private DeliverySlot deliverySlot;
    // Pay as much as the wallet holds, and the rest by card
    private Boolean useWallet;

    /** A booked delivery: the day (ISO date) and the window. */
    public record DeliverySlot(java.time.LocalDate date, Order.DeliveryWindow window) {}

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(ShippingAddress shippingAddress) { this.shippingAddress = shippingAddress; }

    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }

    public Order.PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(Order.PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public Boolean getUseWallet() { return useWallet; }
    public void setUseWallet(Boolean useWallet) { this.useWallet = useWallet; }

    public DeliverySlot getDeliverySlot() { return deliverySlot; }
    public void setDeliverySlot(DeliverySlot deliverySlot) { this.deliverySlot = deliverySlot; }

    /**
     * A new order holding only the caller's fields, under the given server-issued id.
     */
    public Order toOrder(String serverOrderId) {
        Order order = new Order();
        order.setOrderId(serverOrderId);
        order.setExternalReference(orderId);
        order.setCustomerId(customerId);
        order.setShippingAddress(shippingAddress);
        // Checked by CheckoutService; a coupon or slot that does not apply refuses the order
        order.setCouponCode(couponCode == null || couponCode.isBlank() ? null : couponCode.strip().toUpperCase(java.util.Locale.ROOT));
        order.setPaymentMethod(paymentMethod);
        order.setWalletRequested(Boolean.TRUE.equals(useWallet));
        if (deliverySlot != null && (deliverySlot.date() != null || deliverySlot.window() != null)) {
            order.setDeliveryDate(deliverySlot.date());
            order.setDeliveryWindow(deliverySlot.window());
        }
        List<OrderItem> lines = new ArrayList<>();
        if (items != null) {
            for (OrderItem item : items) {
                if (item != null) {
                    // The catalogue sets the price and tax; a price in the request is ignored
                    lines.add(new OrderItem(item.getSku(), item.getQuantity(), Money.ZERO));
                }
            }
        }
        order.setItems(lines);
        return order;
    }
}
