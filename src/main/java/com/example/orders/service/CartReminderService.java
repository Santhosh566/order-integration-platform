package com.example.orders.service;

import com.example.orders.model.CustomerNotification;
import com.example.orders.money.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Emails customers who left items in their cart without ordering ("abandoned carts"), and closes carts left
 * for a month. Run by CartReminderRoute.
 *
 * Reminders are timed from the customer's last change to the cart (orders.cart.reminder-after, 1 h and 24 h).
 * The last one carries a personal coupon, at most once a month per customer, so leaving a cart is not a way to
 * get a discount. Nothing is sent at night, to customers who turned reminders off or were deactivated, or when
 * nothing in the cart can be bought now. Each cart is handled in its own transaction under a row lock that
 * other pods skip, so a reminder goes out once however many pods run; the email goes through the outbox.
 */
@Service
public class CartReminderService {

    private static final Logger log = LoggerFactory.getLogger(CartReminderService.class);

    /** Carts handled per run; the rest wait for the next. */
    static final int BATCH = 200;
    private static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CartService carts;
    private final CustomerNotificationService notifications;
    private final Clock clock;
    private final List<Duration> reminderAfter;
    private final BigDecimal couponPercent;
    private final BigDecimal couponMaxDiscount;
    private final Duration couponValidFor;
    private final Duration couponOnceEvery;
    private final LocalTime quietFrom;
    private final LocalTime quietUntil;
    private final Duration expireAfter;
    private final String shopUrl;
    private final SecureRandom random = new SecureRandom();

    public CartReminderService(JdbcTemplate jdbc, TransactionTemplate tx, CartService carts,
                               CustomerNotificationService notifications, Clock clock,
                               @Value("${orders.cart.reminder-after:PT1H,PT24H}") String reminderAfter,
                               @Value("${orders.cart.coupon-percent:5}") BigDecimal couponPercent,
                               @Value("${orders.cart.coupon-max-discount:500}") BigDecimal couponMaxDiscount,
                               @Value("${orders.cart.coupon-valid-for:PT48H}") Duration couponValidFor,
                               @Value("${orders.cart.coupon-once-every:P30D}") Duration couponOnceEvery,
                               @Value("${orders.cart.quiet-from:21:00}") LocalTime quietFrom,
                               @Value("${orders.cart.quiet-until:09:00}") LocalTime quietUntil,
                               @Value("${orders.cart.expire-after:P30D}") Duration expireAfter,
                               @Value("${orders.shop-url:http://localhost:5173}") String shopUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.carts = carts;
        this.notifications = notifications;
        this.clock = clock;
        this.reminderAfter = reminderAfter == null || reminderAfter.isBlank() ? List.of()
                : Arrays.stream(reminderAfter.split(",")).map(String::strip).map(Duration::parse).sorted().toList();
        this.couponPercent = couponPercent;
        this.couponMaxDiscount = couponMaxDiscount;
        this.couponValidFor = couponValidFor;
        this.couponOnceEvery = couponOnceEvery;
        this.quietFrom = quietFrom;
        this.quietUntil = quietUntil;
        this.expireAfter = expireAfter;
        this.shopUrl = shopUrl.replaceAll("/+$", "");
    }

    /** Sends the reminders that are due now; returns how many went out. */
    public int remindDue() {
        if (reminderAfter.isEmpty() || quietNow()) {
            return 0;
        }
        String delays = delays();
        List<Long> due = jdbc.queryForList("select c.cart_id from carts c " + DUE + " order by c.updated_at limit " + BATCH,
                Long.class, delays, delays);
        int sent = 0;
        for (Long cartId : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> remind(cartId, delays)))) {
                    sent++;
                }
            } catch (RuntimeException e) {
                // One cart that fails must not stop the others; it is tried again on the next run
                log.warn("[CART] Reminder for cart {} failed: {}", cartId, e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("[CART] Sent {} cart reminder(s)", sent);
        }
        return sent;
    }

    /** Closes carts nobody touched for orders.cart.expire-after. */
    public int expire() {
        int closed = jdbc.update("update carts set status = 'EXPIRED', closed_at = now() where status = 'ACTIVE' "
                + "and updated_at < now() - make_interval(secs => ?)", (double) expireAfter.toSeconds());
        if (closed > 0) {
            log.info("[CART] Closed {} cart(s) left for more than {} days", closed, expireAfter.toDays());
        }
        return closed;
    }

    /** One cart, under its row lock; false when another pod has it or nothing was sent. */
    private boolean remind(long cartId, String delays) {
        List<Map<String, Object>> found = jdbc.queryForList("select c.customer_id, c.reminders_sent, u.full_name from carts c "
                + DUE + " and c.cart_id = ? for update of c skip locked", delays, delays, cartId);
        if (found.isEmpty()) {
            return false;
        }
        String customer = (String) found.get(0).get("customer_id");
        int number = ((Number) found.get(0).get("reminders_sent")).intValue();
        String name = (String) found.get(0).get("full_name");
        jdbc.update("update carts set checked_at = now() where cart_id = ?", cartId);

        List<CartService.CartLine> lines = carts.lines(cartId);
        List<CartService.CartLine> buyable = lines.stream().filter(CartService.CartLine::available).toList();
        if (buyable.isEmpty()) {
            // Looked at again in an hour, in case the stock comes back
            log.info("[CART] Cart {}: nothing in it can be bought now, no reminder", cartId);
            return false;
        }
        boolean last = number == reminderAfter.size() - 1;
        Coupon coupon = last && number > 0 ? personalCoupon(customer) : null;

        notifications.queue(new CustomerNotification(null, customer, subject(buyable, number, coupon),
                text(name, lines, buyable, coupon)));
        jdbc.update("update carts set reminders_sent = reminders_sent + 1, last_reminded_at = now(), "
                + "reminder_coupon = coalesce(?, reminder_coupon) where cart_id = ?", coupon == null ? null : coupon.code(), cartId);
        log.info("[CART] Reminder {} for cart {} of {}{}", number + 1, cartId, customer,
                coupon == null ? "" : " with coupon " + coupon.code());
        return true;
    }

    private record Coupon(String code, LocalDateTime endsAt) {}

    /** A single-use coupon for this customer, unless they had one recently or coupons are off. */
    private Coupon personalCoupon(String customer) {
        if (couponPercent == null || couponPercent.signum() <= 0) {
            return null;
        }
        Integer recent = jdbc.queryForObject("select count(*) from coupons where customer_id = ? and created_by = 'cart-reminder' "
                + "and created_at > now() - make_interval(secs => ?)", Integer.class, customer, (double) couponOnceEvery.toSeconds());
        if (recent != null && recent > 0) {
            return null;
        }
        LocalDateTime endsAt = LocalDateTime.now(clock).plus(couponValidFor).withNano(0);
        String description = couponPercent.stripTrailingZeros().toPlainString() + "% off your cart"
                + (couponMaxDiscount != null ? ", up to " + Money.format(couponMaxDiscount, "INR") : "") + ", just for you";
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "CART" + randomCode(6);
            int made = jdbc.update("insert into coupons (code, description, discount_type, discount_value, max_discount, "
                    + "min_order_value, ends_at, usage_limit, per_customer_limit, created_by, customer_id) "
                    + "values (?, ?, 'PERCENT', ?, ?, 0, ?, 1, 1, 'cart-reminder', ?) on conflict (code) do nothing",
                    code, description, couponPercent, couponMaxDiscount, endsAt, customer);
            if (made == 1) {
                return new Coupon(code, endsAt);
            }
        }
        log.warn("[CART] Could not find a free coupon code for {}", customer);
        return null;
    }

    private String subject(List<CartService.CartLine> buyable, int number, Coupon coupon) {
        if (coupon != null) {
            return couponPercent.stripTrailingZeros().toPlainString() + "% off your cart, for the next "
                    + couponValidFor.toHours() + " hours";
        }
        int units = buyable.stream().mapToInt(CartService.CartLine::quantity).sum();
        return number == 0
                ? "You left " + units + (units == 1 ? " item" : " items") + " in your cart"
                : "Your cart is still waiting";
    }

    private String text(String name, List<CartService.CartLine> lines, List<CartService.CartLine> buyable, Coupon coupon) {
        StringBuilder text = new StringBuilder();
        text.append("Hi ").append(name == null || name.isBlank() ? "there" : name.strip().split("\\s+")[0]).append(",\n\n");
        text.append("You left these in your cart:\n\n");
        BigDecimal total = BigDecimal.ZERO;
        for (CartService.CartLine line : buyable) {
            BigDecimal price = Money.of(BigDecimal.valueOf(line.product().price()));
            total = total.add(price.multiply(BigDecimal.valueOf(line.quantity())));
            text.append("  • ").append(line.quantity()).append(" × ").append(line.name()).append(" — ")
                    .append(Money.format(price, "INR"));
            if (line.priceDropped()) {
                text.append(" (was ").append(Money.format(line.priceSeen(), "INR")).append(")");
            }
            if ("FEW_LEFT".equals(line.product().stockStatus())) {
                text.append(" · only a few left");
            }
            text.append('\n');
        }
        String gone = lines.stream().filter(l -> !l.available()).map(CartService.CartLine::name).collect(Collectors.joining(", "));
        if (!gone.isEmpty()) {
            text.append("\nNot available right now: ").append(gone).append('\n');
        }
        text.append("\nTotal now: ").append(Money.format(Money.of(total), "INR")).append(" (GST included)\n");
        String link = shopUrl + "/cart";
        if (coupon != null) {
            text.append("\nUse code ").append(coupon.code()).append(" for ")
                    .append(couponPercent.stripTrailingZeros().toPlainString()).append("% off");
            if (couponMaxDiscount != null) {
                text.append(" (up to ").append(Money.format(couponMaxDiscount, "INR")).append(")");
            }
            text.append(" until ").append(coupon.endsAt().format(UNTIL))
                    .append(". It is for you only and works once.\n");
            link += "?coupon=" + coupon.code();
        }
        text.append("\nOpen your cart: ").append(link).append('\n');
        text.append("\nYou get this email because you left items in your cart. To stop these emails, turn off "
                + "cart reminders in your profile: ").append(shopUrl).append("/profile\n");
        return text.toString();
    }

    private boolean quietNow() {
        return quiet(LocalTime.now(clock), quietFrom, quietUntil);
    }

    /** from..until, which may run past midnight (21:00..09:00); the same time twice is never quiet. */
    static boolean quiet(LocalTime now, LocalTime from, LocalTime until) {
        if (from.equals(until)) {
            return false;
        }
        return from.isBefore(until)
                ? !now.isBefore(from) && now.isBefore(until)
                : !now.isBefore(from) || now.isBefore(until);
    }

    /** Seconds before each reminder, as a Postgres array: "{3600,86400}". */
    private String delays() {
        return reminderAfter.stream().map(d -> String.valueOf(d.toSeconds())).collect(Collectors.joining(",", "{", "}"));
    }

    private String randomCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(CODE_LETTERS.charAt(random.nextInt(CODE_LETTERS.length())));
        }
        return code.toString();
    }

    /**
     * Carts due a reminder: open, with reminders left, quiet since the next reminder's delay, not looked at in
     * the last hour, of a customer who is active and has not turned reminders off. Takes the delays array twice.
     */
    private static final String DUE = "join app_users u on u.user_id = c.customer_id "
            + "left join user_profiles p on p.user_id = c.customer_id "
            + "where c.status = 'ACTIVE' and c.reminders_sent < cardinality(?::bigint[]) "
            + "and c.updated_at <= now() - make_interval(secs => ((?::bigint[])[c.reminders_sent + 1])::double precision) "
            + "and (c.checked_at is null or c.checked_at <= now() - interval '1 hour') "
            + "and u.deactivated_at is null and coalesce(p.cart_reminders, true)";
}
