package com.example.orders.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The coupons the shop advertises on products: "Extra 20% off with DIWALI20" on a card, every coupon
 * that applies on the product page, and the products a coupon is for (/shop?offer=DIWALI20).
 *
 * Only live coupons count (on, inside their dates, not used up). Whether this customer may still use
 * one (per-customer limit, first order only) is checked at checkout, as before.
 */
@Service
public class ProductOffers {

    /**
     * A coupon that takes money off a product.
     *
     * @param targeted true when the coupon is for some products only (this one among them); false when
     *                 it is for any order
     */
    public record ProductOffer(String code, String description, CheckoutService.DiscountType discountType,
                               BigDecimal discountValue, BigDecimal maxDiscount, BigDecimal minOrderValue,
                               LocalDateTime endsAt, boolean firstOrderOnly, boolean targeted) {

        static ProductOffer of(CheckoutService.Coupon c) {
            return new ProductOffer(c.code(), c.description(), c.discountType(), c.discountValue(), c.maxDiscount(),
                    c.minOrderValue(), c.endsAt(), c.firstOrderOnly(), c.targeted());
        }
    }

    /**
     * The products a live coupon is for.
     *
     * @param wholeOrder  true when it is for any product; skus and categoryIds are then empty
     * @param categoryIds the categories listed and the sub-categories of main ones among them
     */
    public record Scope(CheckoutService.Coupon coupon, boolean wholeOrder, Set<String> skus, Set<Long> categoryIds) {}

    private final CheckoutService checkout;
    private final CouponTargets targets;

    public ProductOffers(CheckoutService checkout, CouponTargets targets) {
        this.checkout = checkout;
        this.targets = targets;
    }

    /**
     * The live coupons for each product, ones for chosen products first, then the easiest to reach.
     *
     * @param categoryBySku each product's category; a product without one maps to null
     */
    @Transactional(readOnly = true)
    public Map<String, List<ProductOffer>> forProducts(Map<String, Long> categoryBySku) {
        Map<String, List<ProductOffer>> result = new LinkedHashMap<>();
        categoryBySku.keySet().forEach(sku -> result.put(sku, new ArrayList<>()));
        List<CheckoutService.Coupon> live = checkout.liveCoupons();
        if (live.isEmpty() || categoryBySku.isEmpty()) {
            return result;
        }
        Map<Long, Long> parents = targets.parents();
        for (CheckoutService.Coupon coupon : live) {
            for (Map.Entry<String, Long> product : categoryBySku.entrySet()) {
                if (!coupon.targeted()
                        || CouponTargets.covers(coupon.appliesTo(), product.getKey(), product.getValue(), parents)) {
                    result.get(product.getKey()).add(ProductOffer.of(coupon));
                }
            }
        }
        Comparator<ProductOffer> order = Comparator.comparing((ProductOffer o) -> !o.targeted())
                .thenComparing(ProductOffer::minOrderValue).thenComparing(ProductOffer::code);
        result.values().forEach(list -> list.sort(order));
        return result;
    }

    /** What a live coupon is for; empty when there is no such coupon or it cannot be used now. */
    @Transactional(readOnly = true)
    public Optional<Scope> scope(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String key = code.strip().toUpperCase(Locale.ROOT);
        Optional<CheckoutService.Coupon> found = checkout.liveCoupons().stream().filter(c -> c.code().equals(key)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CheckoutService.Coupon coupon = found.get();
        if (!coupon.targeted()) {
            return Optional.of(new Scope(coupon, true, Set.of(), Set.of()));
        }
        Set<String> skus = new HashSet<>();
        Set<Long> cats = new HashSet<>();
        for (CouponTargets.Target t : coupon.appliesTo()) {
            if (t.sku() != null) {
                skus.add(t.sku());
            } else {
                cats.add(t.categoryId());
            }
        }
        // A main category's sub-categories, as at checkout
        Map<Long, Long> parents = targets.parents();
        parents.forEach((child, parent) -> {
            if (cats.contains(parent)) {
                cats.add(child);
            }
        });
        return Optional.of(new Scope(coupon, false, skus, cats));
    }

    /** Is this coupon live now (for promotions pointing at it). */
    public boolean isLive(String code) {
        return checkout.liveCoupons().stream().anyMatch(c -> c.code().equals(code));
    }
}
