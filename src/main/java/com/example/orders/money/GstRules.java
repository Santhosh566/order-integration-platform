package com.example.orders.money;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * GST rates that depend on the price rather than the product alone.
 *
 * APPAREL_2500: clothing and footwear are taxed at 5% when the value of a piece (before GST) is at
 * most Rs 2,500, and at 18% above (as revised in September 2025). Shop prices include GST, so the
 * piece's value is the price taken back out at 5%: Rs 2,625 incl. GST is exactly Rs 2,500 at 5%.
 * The rates of real products should be confirmed with an accountant.
 */
public final class GstRules {

    public static final String APPAREL_2500 = "APPAREL_2500";

    static final BigDecimal LOW_RATE = new BigDecimal("5");
    static final BigDecimal HIGH_RATE = new BigDecimal("18");
    static final BigDecimal THRESHOLD = new BigDecimal("2500");

    private GstRules() {}

    /** The rate for a price including GST under the rule, or null when there is no rule. */
    public static BigDecimal rateFor(String rule, BigDecimal priceInclGst) {
        if (rule == null || priceInclGst == null) {
            return null;
        }
        if (APPAREL_2500.equals(rule)) {
            BigDecimal valueAtLowRate = priceInclGst.multiply(new BigDecimal("100"))
                    .divide(new BigDecimal("105"), MathContext.DECIMAL64);
            return valueAtLowRate.compareTo(THRESHOLD) <= 0 ? LOW_RATE : HIGH_RATE;
        }
        throw new IllegalArgumentException("Unknown GST rule " + rule);
    }
}
