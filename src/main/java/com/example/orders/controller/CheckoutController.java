package com.example.orders.controller;

import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderRequest;
import com.example.orders.security.CurrentUser;
import com.example.orders.service.CatalogService;
import com.example.orders.service.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checkout for customers: the server's price for the cart (with the coupon and GST), the delivery days
 * that can be booked, and the coupons on offer. Coupons are managed under /api/admin/coupons.
 */
@RestController
public class CheckoutController {

    /** A line as it would be charged. */
    public record QuoteLine(String sku, String name, int quantity, BigDecimal unitPrice, BigDecimal listAmount,
                            BigDecimal discount, BigDecimal lineTotal, BigDecimal gstRate) {}

    /**
     * What the order would cost if placed now.
     *
     * @param couponCode  the coupon applied, or null
     * @param couponError why the coupon asked for does not apply; the quote is then without it
     */
    public record Quote(List<QuoteLine> items, BigDecimal itemsTotal, BigDecimal discount, BigDecimal total,
                        BigDecimal taxTotal, BigDecimal cgstTotal, BigDecimal sgstTotal, BigDecimal igstTotal,
                        String currency, String couponCode, String couponError,
                        CheckoutService.CashOnDelivery cashOnDelivery, ShipsFrom shipsFrom, String shippingProblem) {}

    /** The warehouse the order would ship from: its city for the customer, and its state for the GST split. */
    public record ShipsFrom(String code, String name, String stateCode) {}

    private final CatalogService catalog;
    private final CheckoutService checkout;
    private final CurrentUser currentUser;
    private final TransactionTemplate readOnly;
    private final com.example.orders.warehouse.WarehouseAllocator allocator;

    public CheckoutController(CatalogService catalog, CheckoutService checkout, CurrentUser currentUser,
                              PlatformTransactionManager transactions, com.example.orders.warehouse.WarehouseAllocator allocator) {
        this.allocator = allocator;
        this.catalog = catalog;
        this.checkout = checkout;
        this.currentUser = currentUser;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Where it ships from, when one warehouse has everything. */
    private ShipsFrom shipsFrom(Order order) {
        com.example.orders.warehouse.WarehouseAllocator.Choice choice = allocator.choose(order);
        return choice.warehouse() == null ? null
                : new ShipsFrom(choice.warehouse().code(), choice.warehouse().name(), choice.warehouse().stateCode());
    }

    /** "Order them separately", when the shop has everything but not in one warehouse; placing it would be refused. */
    private String shippingProblem(Order order) {
        return allocator.choose(order).problem();
    }

    @PostMapping("/api/checkout/quote")
    public Quote quote(@RequestBody OrderRequest request) {
        return readOnly.execute(tx -> {
            Order order = request.toOrder(null);
            order.setCustomerId(currentUser.require().userId());
            String couponError = null;
            try {
                catalog.price(order, false);
            } catch (CheckoutService.CouponException e) {
                couponError = e.getMessage();
                order.setCouponCode(null);
                catalog.price(order, false);
            }
            BigDecimal itemsTotal = order.getItems().stream().map(OrderItem::listAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new Quote(order.getItems().stream().map(i -> new QuoteLine(i.getSku(), i.getProductName(), i.getQuantity(),
                    i.getPrice(), i.listAmount(), i.getDiscount(), i.getLineTotal(), i.getGstRate())).toList(),
                    itemsTotal, order.getDiscountTotal(), order.getTotal(), order.getTaxTotal(), order.getCgstTotal(),
                    order.getSgstTotal(), order.getIgstTotal(), order.getCurrency(), order.getCouponCode(), couponError,
                    checkout.cashOnDelivery(order.getCustomerId(), order.getTotal()), shipsFrom(order), shippingProblem(order));
        });
    }

    @GetMapping("/api/checkout/slots")
    public CheckoutService.Slots slots(@RequestParam String pin) {
        return readOnly.execute(tx -> checkout.slots(pin));
    }

    @GetMapping("/api/checkout/offers")
    public List<CheckoutService.Offer> offers() {
        return readOnly.execute(tx -> checkout.offers(currentUser.require().userId()));
    }

    @GetMapping("/api/admin/coupons")
    public List<CheckoutService.Coupon> coupons() {
        return checkout.coupons();
    }

    @PostMapping("/api/admin/coupons")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutService.Coupon create(@RequestBody CheckoutService.NewCoupon request) {
        return checkout.createCoupon(request, currentUser.require().userId());
    }

    @PatchMapping("/api/admin/coupons/{code}")
    public CheckoutService.Coupon change(@PathVariable String code, @RequestBody CheckoutService.CouponChange change) {
        return checkout.changeCoupon(code, change, currentUser.require().userId());
    }
}
