package com.example.orders.service;

import com.example.orders.delivery.DeliveryEstimator;
import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.money.IndianState;
import com.example.orders.money.Money;
import org.apache.camel.Body;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The customer's checkout choices, checked on every order and on every price quote:
 *
 * - A coupon takes money off the order, shared across the lines by their value, before GST is worked
 *   out (CatalogService calls applyCoupon between pricing and tax). A coupon for some products only
 *   (CouponTargets) counts and discounts only their lines.
 * - Cash on delivery is offered up to a total, and not to customers who refused COD parcels before.
 * - A delivery slot is a day from the standard delivery date onwards (skipping Sundays and holidays)
 *   and a window, while that window still has room in the delivery region.
 *
 * Coupon uses and slot bookings are counted from the orders themselves: an order that ends unfulfilled
 * (cancelled, out of stock, payment failed, ...) gives them back without any bookkeeping. While an order
 * is placed, the coupon's row is locked and the slot holds an advisory lock, so two orders cannot both
 * take the last use or the last place.
 */
@Service("checkoutService")
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    /** Thrown for a coupon that does not apply; a quote reports it instead of failing. */
    public static class CouponException extends InvalidOrderException {
        public CouponException(String message) {
            super(message);
        }
    }

    public enum DiscountType { PERCENT, FLAT }

    /** @param appliesTo the products and categories it is for; empty for the whole order */
    public record Coupon(String code, String description, DiscountType discountType, BigDecimal discountValue,
                         BigDecimal maxDiscount, BigDecimal minOrderValue, LocalDateTime startsAt, LocalDateTime endsAt,
                         Integer usageLimit, int perCustomerLimit, boolean firstOrderOnly, boolean active,
                         LocalDateTime createdAt, String createdBy, LocalDateTime updatedAt, String updatedBy,
                         int used, List<CouponTargets.Target> appliesTo) {

        Coupon withTargets(List<CouponTargets.Target> targets) {
            return new Coupon(code, description, discountType, discountValue, maxDiscount, minOrderValue, startsAt,
                    endsAt, usageLimit, perCustomerLimit, firstOrderOnly, active, createdAt, createdBy, updatedAt, updatedBy, used,
                    List.copyOf(targets));
        }

        /** For the whole order, or only for some products. */
        public boolean targeted() {
            return !appliesTo.isEmpty();
        }
    }

    /**
     * A coupon the customer can use now, for the offers list at checkout.
     *
     * @param appliesTo names of the products and categories it is for; empty for the whole order
     */
    public record Offer(String code, String description, BigDecimal minOrderValue, List<String> appliesTo) {}

    /**
     * What an admin sends to create a coupon. skus and categoryIds make it a coupon for those products only;
     * leave both out for the whole order.
     */
    public record NewCoupon(String code, String description, DiscountType discountType, BigDecimal discountValue,
                            BigDecimal maxDiscount, BigDecimal minOrderValue, LocalDateTime startsAt,
                            LocalDateTime endsAt, Integer usageLimit, Integer perCustomerLimit, Boolean firstOrderOnly,
                            List<String> skus, List<Long> categoryIds) {}

    /**
     * What an admin may change later: switch it off or on, move or remove its end, or change what it is for
     * (skus and categoryIds together replace the targets; both empty makes it a whole-order coupon).
     * Null leaves a field; clearEndsAt true removes the end, so it runs until switched off.
     * Orders already placed keep the discount they got; carts are priced by the new terms from now on.
     * The code, the discount and the limits stay as they were: customers have been told them.
     */
    public record CouponChange(Boolean active, LocalDateTime endsAt, Boolean clearEndsAt,
                               List<String> skus, List<Long> categoryIds) {}

    public record Window(Order.DeliveryWindow window, String label, boolean available, int placesLeft) {}

    public record SlotDay(LocalDate date, List<Window> windows) {}

    /**
     * @param reason why no day can be booked, or null
     * @param standardBy the standard delivery's latest date, when known
     */
    public record Slots(boolean bookable, String reason, LocalDate standardBy, List<SlotDay> days) {}

    public record CashOnDelivery(boolean available, String reason, BigDecimal maxOrderValue) {}

    static final Map<Order.DeliveryWindow, String> WINDOW_LABELS = Map.of(
            Order.DeliveryWindow.MORNING, "9 AM – 1 PM",
            Order.DeliveryWindow.AFTERNOON, "1 PM – 5 PM",
            Order.DeliveryWindow.EVENING, "5 PM – 9 PM");

    private final JdbcTemplate jdbc;
    private final WalletService wallets;
    private final CouponTargets targets;
    private final DeliveryEstimator estimator;
    private final BigDecimal codMaxOrderValue;
    private final int codRefusalsAllowed;
    private final int slotCapacity;
    private final int slotDays;

    public CheckoutService(JdbcTemplate jdbc, WalletService wallets, CouponTargets targets, DeliveryEstimator estimator,
                           @Value("${orders.checkout.cod-max-order-value:50000}") BigDecimal codMaxOrderValue,
                           @Value("${orders.checkout.cod-refusals-allowed:2}") int codRefusalsAllowed,
                           @Value("${orders.checkout.slot-capacity:40}") int slotCapacity,
                           @Value("${orders.checkout.slot-days:6}") int slotDays) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.targets = targets;
        this.estimator = estimator;
        this.codMaxOrderValue = Money.of(codMaxOrderValue);
        this.codRefusalsAllowed = codRefusalsAllowed;
        this.slotCapacity = slotCapacity;
        this.slotDays = slotDays;
    }

    // ── Coupons ────────────────────────────────────────────────────────────

    /**
     * Shares the coupon's discount across the order's lines, which must carry their catalogue prices.
     * Without a coupon every line's discount is cleared.
     *
     * @param lock true when placing the order: the coupon's row stays locked until it is saved
     * @throws CouponException when the coupon does not exist or does not apply to this order
     */
    public void applyCoupon(Order order, boolean lock) {
        order.getItems().forEach(i -> i.setDiscount(BigDecimal.ZERO));
        String code = order.getCouponCode();
        if (code == null || code.isBlank()) {
            order.setCouponCode(null);
            return;
        }
        code = code.strip().toUpperCase(Locale.ROOT);
        order.setCouponCode(code);
        if (lock) {
            // Locked first, on its own: the counts below then run after any order holding it has committed
            jdbc.queryForList("select code from coupons where code = ? for update", String.class, code);
        }
        List<Coupon> found = jdbc.query(COUPON + "where c.code = ?", COUPON_ROW, code);
        Coupon coupon = found.isEmpty() ? null : found.get(0);
        if (coupon == null || !coupon.active()) {
            throw new CouponException("Coupon " + code + " is not valid");
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.startsAt() != null && now.isBefore(coupon.startsAt())) {
            throw new CouponException("Coupon " + code + " is not valid yet");
        }
        if (coupon.endsAt() != null && !now.isBefore(coupon.endsAt())) {
            throw new CouponException("Coupon " + code + " has expired");
        }
        // A coupon for some products counts, and takes money off, only their lines
        List<CouponTargets.Target> appliesTo = targets.of(code);
        List<OrderItem> lines = order.getItems();
        if (!appliesTo.isEmpty()) {
            java.util.Set<String> eligible = targets.eligible(code, order.getItems().stream().map(OrderItem::getSku).toList());
            lines = order.getItems().stream().filter(i -> eligible.contains(i.getSku())).toList();
            if (lines.isEmpty()) {
                throw new CouponException("Coupon " + code + " is for " + CouponTargets.describe(appliesTo)
                        + " only, and none of them is in your cart");
            }
        }
        BigDecimal value = lines.stream().map(OrderItem::listAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (value.compareTo(coupon.minOrderValue()) < 0) {
            throw new CouponException(appliesTo.isEmpty()
                    ? "Coupon " + code + " needs an order of at least " + Money.format(coupon.minOrderValue(), "INR")
                            + "; add " + Money.format(coupon.minOrderValue().subtract(value), "INR") + " more"
                    : "Coupon " + code + " needs at least " + Money.format(coupon.minOrderValue(), "INR") + " of "
                            + CouponTargets.describe(appliesTo) + "; add " + Money.format(coupon.minOrderValue().subtract(value), "INR")
                            + " more of them");
        }
        if (coupon.usageLimit() != null && coupon.used() >= coupon.usageLimit()) {
            throw new CouponException("Coupon " + code + " has been fully used");
        }
        String customer = order.getCustomerId();
        if (customer != null) {
            int mine = count("select count(*) from orders where coupon_code = ? and customer_id = ? and order_id <> ? "
                    + "and deleted_at is null and status not in (" + UNFULFILLED + ")", code, customer, idOf(order));
            if (mine >= coupon.perCustomerLimit()) {
                throw new CouponException(coupon.perCustomerLimit() == 1
                        ? "You have already used coupon " + code
                        : "You have used coupon " + code + " " + mine + " times, the most it allows");
            }
            if (coupon.firstOrderOnly() && count("select count(*) from orders where customer_id = ? and order_id <> ? "
                    + "and deleted_at is null and status not in (" + UNFULFILLED + ")", customer, idOf(order)) > 0) {
                throw new CouponException("Coupon " + code + " is for your first order only");
            }
        }

        BigDecimal discount = switch (coupon.discountType()) {
            case PERCENT -> Money.percent(value, coupon.discountValue());
            case FLAT -> coupon.discountValue();
        };
        if (coupon.maxDiscount() != null) {
            discount = discount.min(coupon.maxDiscount());
        }
        // Never more than the lines it is for, and at least a rupee is paid for them
        discount = Money.of(discount.min(value.subtract(BigDecimal.ONE)).max(BigDecimal.ZERO));
        share(lines, discount, value);
    }

    /**
     * Each line takes its part of the discount by value, in paise; the largest line takes what rounding leaves.
     */
    static void share(List<OrderItem> items, BigDecimal discount, BigDecimal value) {
        if (discount.signum() == 0 || value.signum() == 0) {
            return;
        }
        OrderItem largest = items.stream().max(Comparator.comparing(OrderItem::listAmount)).orElseThrow();
        BigDecimal given = BigDecimal.ZERO;
        for (OrderItem item : items) {
            if (item == largest) {
                continue;
            }
            BigDecimal part = discount.multiply(item.listAmount()).divide(value, 2, RoundingMode.DOWN);
            item.setDiscount(part);
            given = given.add(part);
        }
        largest.setDiscount(discount.subtract(given));
    }

    /** Coupons the customer can use now, best first; the shop shows them at checkout. */
    public List<Offer> offers(String customerId) {
        List<Offer> offers = new ArrayList<>();
        for (Coupon c : liveCoupons()) {
            int mine = count("select count(*) from orders where coupon_code = ? and customer_id = ? and deleted_at is null "
                    + "and status not in (" + UNFULFILLED + ")", c.code(), customerId);
            if (mine >= c.perCustomerLimit()) {
                continue;
            }
            if (c.firstOrderOnly() && count("select count(*) from orders where customer_id = ? and deleted_at is null "
                    + "and status not in (" + UNFULFILLED + ")", customerId) > 0) {
                continue;
            }
            offers.add(new Offer(c.code(), c.description(), c.minOrderValue(),
                    c.appliesTo().stream().map(CouponTargets.Target::name).toList()));
        }
        return offers;
    }

    /**
     * Coupons anyone could use now: on, inside their dates, and not used up. Per-customer limits are not
     * checked here. Cheapest to reach first.
     */
    public List<Coupon> liveCoupons() {
        return withTargets(jdbc.query(COUPON + "where c.active and (c.starts_at is null or c.starts_at <= now()) "
                + "and (c.ends_at is null or c.ends_at > now()) order by c.min_order_value, c.code", COUPON_ROW).stream()
                .filter(c -> c.usageLimit() == null || c.used() < c.usageLimit())
                .toList());
    }

    public List<Coupon> coupons() {
        return withTargets(jdbc.query(COUPON + "order by c.active desc, c.created_at desc", COUPON_ROW));
    }

    private List<Coupon> withTargets(List<Coupon> coupons) {
        Map<String, List<CouponTargets.Target>> all = targets.of(coupons.stream().map(Coupon::code).toList());
        return coupons.stream().map(c -> c.withTargets(all.getOrDefault(c.code(), List.of()))).toList();
    }

    @org.springframework.transaction.annotation.Transactional
    public Coupon createCoupon(NewCoupon request, String admin) {
        if (request == null) {
            throw new InvalidOrderException("Coupon details are required");
        }
        String code = request.code() == null ? "" : request.code().strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{3,30}")) {
            throw new InvalidOrderException("The code must be 3 to 30 letters and digits, e.g. DIWALI25");
        }
        String description = request.description() == null ? "" : request.description().strip();
        if (description.isEmpty() || description.length() > 200) {
            throw new InvalidOrderException("Describe the offer in 1 to 200 characters; customers see it");
        }
        if (request.discountType() == null || request.discountValue() == null || request.discountValue().signum() <= 0) {
            throw new InvalidOrderException("Give the discount: a percent or an amount in rupees");
        }
        if (request.discountType() == DiscountType.PERCENT && request.discountValue().compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new InvalidOrderException("A percent discount can be at most 90%");
        }
        if (request.maxDiscount() != null && request.maxDiscount().signum() <= 0) {
            throw new InvalidOrderException("The most it takes off must be more than ₹0");
        }
        if (request.minOrderValue() != null && request.minOrderValue().signum() < 0) {
            throw new InvalidOrderException("The minimum order cannot be negative");
        }
        if (request.discountType() == DiscountType.FLAT && request.minOrderValue() != null
                && request.minOrderValue().signum() > 0 && request.discountValue().compareTo(request.minOrderValue()) >= 0) {
            throw new InvalidOrderException("A flat discount must be less than the minimum order");
        }
        if (request.startsAt() != null && request.endsAt() != null && !request.endsAt().isAfter(request.startsAt())) {
            throw new InvalidOrderException("The end must be after the start");
        }
        if (request.usageLimit() != null && request.usageLimit() <= 0 || request.perCustomerLimit() != null && request.perCustomerLimit() <= 0) {
            throw new InvalidOrderException("Limits must be at least 1");
        }
        try {
            jdbc.update("insert into coupons (code, description, discount_type, discount_value, max_discount, min_order_value, "
                    + "starts_at, ends_at, usage_limit, per_customer_limit, first_order_only, created_by) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    code, description, request.discountType().name(), request.discountValue(),
                    request.discountType() == DiscountType.PERCENT ? request.maxDiscount() : null,
                    request.minOrderValue() == null ? BigDecimal.ZERO : request.minOrderValue(),
                    request.startsAt(), request.endsAt(), request.usageLimit(),
                    request.perCustomerLimit() == null ? 1 : request.perCustomerLimit(),
                    Boolean.TRUE.equals(request.firstOrderOnly()), admin);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new InvalidOrderException("There is already a coupon " + code);
        }
        targets.replace(code, request.skus(), request.categoryIds());
        log.info("[COUPONS] {} created {}", admin, code);
        return coupon(code);
    }

    @org.springframework.transaction.annotation.Transactional
    public Coupon changeCoupon(String code, CouponChange change, String admin) {
        String key = code.toUpperCase(Locale.ROOT);
        Coupon before = coupon(key);
        if (change == null) {
            return before;
        }
        boolean clearEnd = Boolean.TRUE.equals(change.clearEndsAt());
        if (clearEnd && change.endsAt() != null) {
            throw new InvalidOrderException("Either give a new end or remove the end, not both");
        }
        if (change.endsAt() != null && before.startsAt() != null && !change.endsAt().isAfter(before.startsAt())) {
            throw new InvalidOrderException("The end must be after the start");
        }
        // Locked like an order placing it, so no order is priced half with the old terms
        jdbc.queryForList("select code from coupons where code = ? for update", String.class, key);
        List<String> changed = new ArrayList<>();
        if (change.skus() != null || change.categoryIds() != null) {
            targets.replace(key, change.skus(), change.categoryIds());
            changed.add("products");
        }
        if (change.active() != null && change.active() != before.active()) {
            jdbc.update("update coupons set active = ? where code = ?", change.active(), key);
            changed.add(change.active() ? "on" : "off");
        }
        if (change.endsAt() != null) {
            jdbc.update("update coupons set ends_at = ? where code = ?", change.endsAt(), key);
            changed.add("end " + change.endsAt());
        } else if (clearEnd && before.endsAt() != null) {
            jdbc.update("update coupons set ends_at = null where code = ?", key);
            changed.add("no end");
        }
        if (!changed.isEmpty()) {
            jdbc.update("update coupons set updated_at = now(), updated_by = ? where code = ?", admin, key);
            log.info("[COUPONS] {} changed {}: {}", admin, key, String.join(", ", changed));
        }
        return coupon(key);
    }

    private Coupon coupon(String code) {
        List<Coupon> found = jdbc.query(COUPON + "where c.code = ?", COUPON_ROW, code);
        if (found.isEmpty()) {
            throw new com.example.orders.exception.NotFoundException("No coupon " + code);
        }
        return withTargets(found).get(0);
    }

    // ── Cash on delivery ───────────────────────────────────────────────────

    public CashOnDelivery cashOnDelivery(String customerId, BigDecimal total) {
        if (total != null && total.compareTo(codMaxOrderValue) > 0) {
            return new CashOnDelivery(false, "Cash on delivery is for orders up to " + Money.format(codMaxOrderValue, "INR")
                    + ". Pay online, or split the order.", codMaxOrderValue);
        }
        if (customerId != null && count("select count(*) from orders where customer_id = ? and payment_method = 'COD' "
                + "and status = 'RETURNED' and created_at > now() - interval '180 days'", customerId) >= codRefusalsAllowed) {
            return new CashOnDelivery(false, "Cash on delivery is not available on your account because earlier "
                    + "cash-on-delivery parcels could not be delivered. Please pay online.", codMaxOrderValue);
        }
        return new CashOnDelivery(true, null, codMaxOrderValue);
    }

    // ── Delivery slots ─────────────────────────────────────────────────────

    /** The days and windows a customer can book for a PIN, with the places left in each. */
    public Slots slots(String pin) {
        DeliveryEstimator.Estimate estimate = estimator.estimate(pin, true);
        if (estimate.outcome() != DeliveryEstimator.Outcome.DELIVERABLE) {
            return new Slots(false, "Choose a delivery address with a valid PIN code first", null, List.of());
        }
        if (estimate.deliveryState() == null) {
            return new Slots(false, "A delivery day can be chosen once we can look up this PIN code", estimate.latest(), List.of());
        }
        IndianState state = estimate.deliveryState();
        List<LocalDate> days = bookableDays(estimate);
        Map<String, Integer> booked = bookings(days, state);
        List<SlotDay> result = new ArrayList<>();
        for (LocalDate day : days) {
            List<Window> windows = new ArrayList<>();
            for (Order.DeliveryWindow w : Order.DeliveryWindow.values()) {
                int left = Math.max(0, slotCapacity - booked.getOrDefault(day + "|" + w, 0));
                windows.add(new Window(w, WINDOW_LABELS.get(w), left > 0, left));
            }
            result.add(new SlotDay(day, windows));
        }
        return new Slots(true, null, estimate.latest(), result);
    }

    /**
     * After pricing, as the order is placed: sets what the wallet pays, checks the payment method and books the slot.
     * Runs inside the order's transaction, so the slot's lock is held until the order is saved.
     */
    public void checkOptions(@Body Order order) {
        wallets.applyToOrder(order);
        if (order.isCashOnDelivery()) {
            CashOnDelivery cod = cashOnDelivery(order.getCustomerId(), order.getTotal());
            if (!cod.available()) {
                throw new InvalidOrderException(cod.reason());
            }
        }
        if (order.getDeliveryDate() == null && order.getDeliveryWindow() == null) {
            return;
        }
        if (order.getDeliveryDate() == null || order.getDeliveryWindow() == null) {
            throw new InvalidOrderException("A delivery slot needs both a day and a time");
        }
        String pin = order.getShippingAddress() == null ? null : order.getShippingAddress().getPostalCode();
        DeliveryEstimator.Estimate estimate = estimator.estimate(pin, true);
        if (estimate.outcome() != DeliveryEstimator.Outcome.DELIVERABLE || estimate.deliveryState() == null) {
            throw new InvalidOrderException("A delivery day cannot be booked for PIN " + pin);
        }
        IndianState state = estimate.deliveryState();
        if (!bookableDays(estimate).contains(order.getDeliveryDate())) {
            throw new InvalidOrderException("Delivery on " + order.getDeliveryDate() + " can no longer be booked. "
                    + "Choose another day, or standard delivery.");
        }
        // One order at a time per window and region, until this transaction ends
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "slot|" + order.getDeliveryDate() + "|"
                + order.getDeliveryWindow() + "|" + state.region());
        int booked = bookings(List.of(order.getDeliveryDate()), state)
                .getOrDefault(order.getDeliveryDate() + "|" + order.getDeliveryWindow(), 0);
        if (booked >= slotCapacity) {
            throw new InvalidOrderException("The " + WINDOW_LABELS.get(order.getDeliveryWindow()) + " slot on "
                    + order.getDeliveryDate() + " has just filled up. Choose another.");
        }
    }

    private List<LocalDate> bookableDays(DeliveryEstimator.Estimate estimate) {
        // From the standard delivery's latest day, so a booked day is one we can keep
        return estimator.deliveryDaysAfter(estimate.latest().minusDays(1), slotDays, estimate.deliveryState());
    }

    /** Live orders booked per "day|window" in the state's delivery region. */
    private Map<String, Integer> bookings(List<LocalDate> days, IndianState state) {
        if (days.isEmpty()) {
            return Map.of();
        }
        List<String> regionCodes = java.util.Arrays.stream(IndianState.values())
                .filter(s -> s.region() == state.region()).map(IndianState::gstCode).toList();
        String dayMarks = days.stream().map(d -> "?").collect(Collectors.joining(", "));
        String codeMarks = regionCodes.stream().map(c -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(days);
        args.addAll(regionCodes);
        Map<String, Integer> counts = new java.util.HashMap<>();
        jdbc.query("select delivery_date, delivery_window, count(*) as n from orders where delivery_date in (" + dayMarks + ") "
                + "and place_of_supply in (" + codeMarks + ") and deleted_at is null and status not in (" + UNFULFILLED + ") "
                + "group by delivery_date, delivery_window", rs -> {
            counts.put(rs.getObject("delivery_date", LocalDate.class) + "|" + rs.getString("delivery_window"), rs.getInt("n"));
        }, args.toArray());
        return counts;
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** A new order has no row yet; its id still keeps it out of its own counts when re-checked. */
    private static String idOf(Order order) {
        return order.getOrderId() == null ? "" : order.getOrderId();
    }

    private static final String UNFULFILLED = OrderStatus.finishedUnfulfilledStatuses().stream()
            .map(s -> "'" + s.name() + "'").sorted().collect(Collectors.joining(", "));

    private static final String COUPON = "select c.*, (select count(*) from orders o where o.coupon_code = c.code "
            + "and o.deleted_at is null and o.status not in (" + UNFULFILLED + ")) as used from coupons c ";

    private static final org.springframework.jdbc.core.RowMapper<Coupon> COUPON_ROW = (rs, n) -> new Coupon(
            rs.getString("code"), rs.getString("description"), DiscountType.valueOf(rs.getString("discount_type")),
            rs.getBigDecimal("discount_value"), rs.getBigDecimal("max_discount"), rs.getBigDecimal("min_order_value"),
            time(rs.getTimestamp("starts_at")), time(rs.getTimestamp("ends_at")),
            (Integer) rs.getObject("usage_limit"), rs.getInt("per_customer_limit"), rs.getBoolean("first_order_only"),
            rs.getBoolean("active"), time(rs.getTimestamp("created_at")), rs.getString("created_by"),
            time(rs.getTimestamp("updated_at")), rs.getString("updated_by"), rs.getInt("used"),
            List.of());

    private static LocalDateTime time(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
