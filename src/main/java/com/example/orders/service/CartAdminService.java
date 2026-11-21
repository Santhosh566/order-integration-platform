package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.OrderStatus;
import com.example.orders.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** How carts end, for the admin: how many were left, reminded and won back, and what is in the open ones. */
@Service
public class CartAdminService {

    /**
     * Carts started in the period, and how they ended so far.
     *
     * @param recoveryRate     recovered ÷ reminded, 0 to 1; null when none was reminded
     * @param recoveredRevenue the totals of the orders that recovered carts, leaving out orders that ended unfulfilled
     * @param open             carts open now, whatever their start; openValue is what they hold at today's prices
     */
    public record Stats(LocalDate from, LocalDate to, int started, int ordered, int reminded, int recovered,
                        Double recoveryRate, BigDecimal recoveredRevenue, int emptied, int expired,
                        int couponsSent, int couponsUsed, int open, BigDecimal openValue) {}

    public record Item(String sku, String name, int quantity) {}

    /** @param value what the lines cost now, GST included (products no longer sold count 0) */
    public record Row(long cartId, String customerId, String customerName, String customerEmail, String status,
                      int itemCount, BigDecimal value, LocalDateTime createdAt, LocalDateTime updatedAt,
                      int remindersSent, LocalDateTime lastRemindedAt, String reminderCoupon, String orderId,
                      List<Item> items) {}

    public record Page(List<Row> items, int totalItems, int page, int size) {}

    private final JdbcTemplate jdbc;

    public CartAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Stats stats(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw new InvalidOrderException("The period must start before it ends");
        }
        Object[] period = {start.atStartOfDay(), end.plusDays(1).atStartOfDay()};
        Map<String, Object> c = jdbc.queryForMap("select count(*) as started, "
                + "count(*) filter (where status = 'ORDERED') as ordered, "
                + "count(*) filter (where reminders_sent > 0) as reminded, "
                + "count(*) filter (where status = 'RECOVERED') as recovered, "
                + "count(*) filter (where status = 'EMPTIED') as emptied, "
                + "count(*) filter (where status = 'EXPIRED') as expired, "
                + "count(*) filter (where reminder_coupon is not null) as coupons_sent "
                + "from carts where created_at >= ? and created_at < ?", period);
        BigDecimal revenue = jdbc.queryForObject("select coalesce(sum(o.total), 0) from carts c join orders o on o.order_id = c.order_id "
                + "where c.status = 'RECOVERED' and c.created_at >= ? and c.created_at < ? and o.deleted_at is null "
                + "and o.status not in (" + UNFULFILLED + ")", BigDecimal.class, period);
        Integer couponsUsed = jdbc.queryForObject("select count(*) from carts c join orders o on o.coupon_code = c.reminder_coupon "
                + "where c.created_at >= ? and c.created_at < ? and o.deleted_at is null and o.status not in (" + UNFULFILLED + ")",
                Integer.class, period);
        Map<String, Object> open = jdbc.queryForMap("select count(distinct c.cart_id) as open, "
                + "coalesce(sum(ci.quantity * case when i.active then i.unit_price else 0 end), 0) as value "
                + "from carts c left join cart_items ci on ci.cart_id = c.cart_id left join inventory i on i.sku = ci.sku "
                + "where c.status = 'ACTIVE'");
        int reminded = n(c.get("reminded"));
        int recovered = n(c.get("recovered"));
        return new Stats(start, end, n(c.get("started")), n(c.get("ordered")), reminded, recovered,
                reminded == 0 ? null : (double) recovered / reminded, Money.of(revenue), n(c.get("emptied")),
                n(c.get("expired")), n(c.get("coupons_sent")), couponsUsed == null ? 0 : couponsUsed,
                n(open.get("open")), Money.of((BigDecimal) open.get("value")));
    }

    /** Carts newest change first; status null for all. */
    @Transactional(readOnly = true)
    public Page list(String status, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        int pageNo = Math.max(page, 0);
        String where = "";
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            try {
                where = "where c.status = ? ";
                args.add(CartService.Status.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT)).name());
            } catch (IllegalArgumentException e) {
                throw new InvalidOrderException("Unknown cart status " + status);
            }
        }
        Integer total = jdbc.queryForObject("select count(*) from carts c " + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add(pageNo * pageSize);
        List<Row> rows = jdbc.query("select c.*, u.full_name, u.email, "
                + "(select coalesce(sum(ci.quantity), 0) from cart_items ci where ci.cart_id = c.cart_id) as item_count, "
                + "(select coalesce(sum(ci.quantity * case when i.active then i.unit_price else 0 end), 0) "
                + "   from cart_items ci join inventory i on i.sku = ci.sku where ci.cart_id = c.cart_id) as value "
                + "from carts c join app_users u on u.user_id = c.customer_id " + where
                + "order by c.updated_at desc, c.cart_id desc limit ? offset ?", (rs, n) -> new Row(
                        rs.getLong("cart_id"), rs.getString("customer_id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("status"), rs.getInt("item_count"), Money.of(rs.getBigDecimal("value")),
                        rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
                        rs.getInt("reminders_sent"), rs.getObject("last_reminded_at", LocalDateTime.class),
                        rs.getString("reminder_coupon"), rs.getString("order_id"), List.of()),
                pageArgs.toArray());
        Map<Long, List<Item>> items = items(rows.stream().map(Row::cartId).toList());
        List<Row> withItems = rows.stream().map(r -> new Row(r.cartId(), r.customerId(), r.customerName(), r.customerEmail(),
                r.status(), r.itemCount(), r.value(), r.createdAt(), r.updatedAt(), r.remindersSent(), r.lastRemindedAt(),
                r.reminderCoupon(), r.orderId(), items.getOrDefault(r.cartId(), List.of()))).toList();
        return new Page(withItems, total == null ? 0 : total, pageNo, pageSize);
    }

    private Map<Long, List<Item>> items(List<Long> cartIds) {
        Map<Long, List<Item>> items = new HashMap<>();
        if (cartIds.isEmpty()) {
            return items;
        }
        String marks = cartIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        jdbc.query("select ci.cart_id, ci.sku, i.product_name, ci.quantity from cart_items ci join inventory i on i.sku = ci.sku "
                + "where ci.cart_id in (" + marks + ") order by ci.added_at, ci.sku", rs -> {
            items.computeIfAbsent(rs.getLong("cart_id"), k -> new ArrayList<>()).add(new Item(rs.getString("sku"),
                    rs.getString("product_name") != null ? rs.getString("product_name") : rs.getString("sku"), rs.getInt("quantity")));
        }, cartIds.toArray());
        return items;
    }

    private static int n(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static final String UNFULFILLED = OrderStatus.finishedUnfulfilledStatuses().stream()
            .map(s -> "'" + s.name() + "'").sorted().collect(Collectors.joining(", "));
}
