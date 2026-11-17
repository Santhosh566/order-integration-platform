package com.example.orders.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Amounts of money: always BigDecimal with two decimals, rounded half up, so totals, tax,
 * payments and refunds add up to the paisa. A double cannot hold 0.10 exactly.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {}

    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal of(String amount) {
        return of(new BigDecimal(amount));
    }

    public static BigDecimal of(long amount) {
        return of(BigDecimal.valueOf(amount));
    }

    /** The part of an amount at a percentage rate, e.g. percent(1000, 9) = 90.00. */
    public static BigDecimal percent(BigDecimal amount, BigDecimal rate) {
        return of(amount.multiply(rate).divide(HUNDRED, SCALE + 6, ROUNDING));
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    /**
     * For people: "₹1,23,456.00" with Indian digit grouping for rupees,
     * "USD 45.00" for other currencies.
     */
    public static String format(BigDecimal amount, String currency) {
        String digits = group(of(amount), currency);
        return "INR".equals(currency) ? "₹" + digits : currency + " " + digits;
    }

    /**
     * Like format, but with the currency code instead of a symbol, for fonts without "₹" (the PDF invoice).
     */
    public static String formatCode(BigDecimal amount, String currency) {
        return currency + " " + group(of(amount), currency);
    }

    /** Lakh and crore grouping for INR (12,34,567.00); groups of three otherwise. */
    private static String group(BigDecimal amount, String currency) {
        String plain = amount.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);
        StringBuilder out = new StringBuilder();
        if ("INR".equals(currency) && whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    out.append(',');
                }
                out.append(head.charAt(i));
            }
            out.append(',').append(whole.substring(whole.length() - 3));
        } else {
            for (int i = 0; i < whole.length(); i++) {
                if (i > 0 && (whole.length() - i) % 3 == 0) {
                    out.append(',');
                }
                out.append(whole.charAt(i));
            }
        }
        return (amount.signum() < 0 ? "-" : "") + out + fraction;
    }
}
