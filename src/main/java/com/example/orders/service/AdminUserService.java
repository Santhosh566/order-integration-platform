package com.example.orders.service;

import com.example.orders.model.OrderStatus;
import com.example.orders.model.Role;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Admins look up any user: a searchable list, and one user's profile with what they have done
 * (orders as a customer, deliveries as a driver). Read-only.
 */
@Service
public class AdminUserService {

    public record UserRow(String userId, String fullName, String email, String phone, List<Role> roles,
                          boolean active, String photoVersion, LocalDateTime createdAt) {}

    public record UserPage(List<UserRow> items, long total, int page, int size) {}

    public record CustomerActivity(long orders, long delivered, long cancelled, BigDecimal spent, long returns,
                                   LocalDateTime lastOrderAt) {}

    public record DriverActivity(long deliveries, long pickups, long failed, BigDecimal earnings,
                                 BigDecimal rating, long ratingCount, LocalDateTime lastDeliveryAt) {}

    /** Set while the user is inactive: who turned them off, when and why. */
    public record Deactivation(LocalDateTime at, String by, String byName, String reason) {}

    public record UserDetail(ProfileService.Profile profile, boolean active, String tier, boolean signInLinked,
                             Deactivation deactivation, CustomerActivity customer, DriverActivity driver) {}

    private static final int MAX_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final ProfileService profiles;

    public AdminUserService(JdbcTemplate jdbc, ProfileService profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
    }

    /**
     * @param q    part of the name, email, phone (digits) or user id; empty for everyone
     * @param role only users with this role; null for all
     */
    @Transactional(readOnly = true)
    public UserPage search(String q, Role role, int page, int size) {
        return search(q, role, null, page, size);
    }

    /** @param active only active (true) or inactive (false) users; null for both */
    @Transactional(readOnly = true)
    public UserPage search(String q, Role role, Boolean active, int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_SIZE);
        int offset = Math.max(page, 0) * limit;
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String text = "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            String digits = q.replaceAll("\\D", "");
            where.append(" and (lower(u.full_name) like ? or lower(coalesce(u.email, '')) like ? or lower(u.user_id) like ?");
            args.add(text);
            args.add(text);
            args.add(text);
            if (digits.length() >= 4) {
                where.append(" or regexp_replace(coalesce(u.phone, ''), '\\D', '', 'g') like ?");
                args.add("%" + digits + "%");
            }
            where.append(")");
        }
        if (active != null) {
            where.append(" and u.active = ?");
            args.add(active);
        }
        if (role != null) {
            where.append(" and exists (select 1 from user_roles r where r.user_id = u.user_id and r.role = ?)");
            args.add(role.name());
        }
        long total = jdbc.queryForObject("select count(*) from app_users u" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<UserRow> rows = jdbc.query("""
                select u.user_id, u.full_name, u.email, u.phone, u.active, u.created_at, p.photo_key, p.photo_updated_at,
                       (select string_agg(r.role, ',' order by r.role) from user_roles r where r.user_id = u.user_id) as roles
                from app_users u left join user_profiles p on p.user_id = u.user_id""" + where
                        + " order by lower(u.full_name), u.user_id limit ? offset ?",
                (rs, n) -> new UserRow(rs.getString("user_id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("phone"), roles(rs.getString("roles")), rs.getBoolean("active"),
                        rs.getString("photo_key") == null ? null : String.valueOf(rs.getObject("photo_updated_at", LocalDateTime.class)),
                        rs.getObject("created_at", LocalDateTime.class)),
                pageArgs.toArray());
        return new UserPage(rows, total, Math.max(page, 0), limit);
    }

    @Transactional(readOnly = true)
    public UserDetail detail(String userId) {
        ProfileService.Profile profile = profiles.profileOf(userId);
        Map<String, Object> user = jdbc.queryForMap("""
                select u.active, u.tier, u.keycloak_subject is not null as linked, u.deactivated_at, u.deactivated_by,
                       u.deactivated_reason, a.full_name as deactivated_by_name
                from app_users u left join app_users a on a.user_id = u.deactivated_by where u.user_id = ?""", userId);
        Deactivation deactivation = Boolean.TRUE.equals(user.get("active")) ? null
                : new Deactivation(time(user.get("deactivated_at")), (String) user.get("deactivated_by"),
                        (String) user.get("deactivated_by_name"), (String) user.get("deactivated_reason"));
        CustomerActivity customer = profile.roles().contains(Role.CUSTOMER) ? customer(userId) : null;
        DriverActivity driver = profile.roles().contains(Role.DELIVERY) ? driver(userId) : null;
        return new UserDetail(profile, (Boolean) user.get("active"), (String) user.get("tier"),
                (Boolean) user.get("linked"), deactivation, customer, driver);
    }

    private CustomerActivity customer(String userId) {
        String revenue = OrderStatus.revenueStatuses().stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) as orders,
                       count(*) filter (where status = 'DELIVERED') as delivered,
                       count(*) filter (where status = 'CANCELLED') as cancelled,
                       coalesce(sum(case when status in (%s) then total - refunded_total
                           - case when payment_method = 'COD' then cancelled_total else 0 end end), 0) as spent,
                       max(created_at) as last_order_at
                from orders where customer_id = ? and deleted_at is null""".formatted(revenue), userId);
        long returns = jdbc.queryForObject("select count(*) from return_requests where customer_id = ?", Long.class, userId);
        return new CustomerActivity(num(row.get("orders")), num(row.get("delivered")), num(row.get("cancelled")),
                (BigDecimal) row.get("spent"), returns, time(row.get("last_order_at")));
    }

    private DriverActivity driver(String userId) {
        Map<String, Object> drops = jdbc.queryForMap("""
                select count(*) filter (where status = 'DELIVERED') as deliveries,
                       count(*) filter (where status = 'FAILED') as failed,
                       coalesce(sum(pay_amount), 0) as pay,
                       max(delivered_at) as last_delivery_at
                from delivery_assignments where delivery_user_id = ?""", userId);
        Map<String, Object> pickups = jdbc.queryForMap("""
                select count(*) filter (where status = 'COLLECTED') as pickups, coalesce(sum(pay_amount), 0) as pay
                from return_pickups where delivery_user_id = ?""", userId);
        Map<String, Object> rating = jdbc.queryForMap(
                "select avg(rating) as average, count(*) as ratings from delivery_ratings where delivery_user_id = ?", userId);
        BigDecimal average = rating.get("average") == null ? null
                : ((BigDecimal) rating.get("average")).setScale(1, java.math.RoundingMode.HALF_UP);
        return new DriverActivity(num(drops.get("deliveries")), num(pickups.get("pickups")), num(drops.get("failed")),
                ((BigDecimal) drops.get("pay")).add((BigDecimal) pickups.get("pay")), average, num(rating.get("ratings")),
                time(drops.get("last_delivery_at")));
    }

    private static List<Role> roles(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(Role::valueOf).toList();
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return (LocalDateTime) value;
    }
}
