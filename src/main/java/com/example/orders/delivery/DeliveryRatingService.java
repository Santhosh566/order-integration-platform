package com.example.orders.delivery;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.AppUser;
import com.example.orders.model.Order;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The customer rates how their order was delivered, 1 to 5 stars and an optional comment, for the
 * driver who handed it over. Allowed for 14 days after delivery, and changeable within that time.
 * Drivers see their average and the comments, never who wrote them.
 */
@Service
public class DeliveryRatingService {

    static final Duration WINDOW = Duration.ofDays(14);

    /**
     * @param canRate   false once the window has closed, or when the order was not delivered by a driver
     * @param reason    why it cannot be rated, or null
     * @param driverName the driver's first name, as the customer met them
     */
    public record Status(String orderId, boolean canRate, String reason, String driverName, LocalDateTime deliveredAt,
                         LocalDateTime closesAt, Integer rating, String comment) {}

    public record Submit(Integer rating, String comment) {}

    private record Delivered(long assignmentId, String driverId, LocalDateTime deliveredAt) {}

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final AppUserRepository users;

    public DeliveryRatingService(JdbcTemplate jdbc, OrderRepository orders, AppUserRepository users) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Status status(String customerId, String orderId) {
        Order order = own(customerId, orderId);
        Delivered delivered = delivered(order.getOrderId());
        Map<String, Object> existing = jdbc.queryForList("select rating, comment from delivery_ratings where order_id = ?", orderId)
                .stream().findFirst().orElse(null);
        Integer rating = existing == null ? null : ((Number) existing.get("rating")).intValue();
        String comment = existing == null ? null : (String) existing.get("comment");
        if (delivered == null) {
            return new Status(orderId, false, "Only orders our own drivers delivered can be rated", null, null, null, rating, comment);
        }
        LocalDateTime closes = delivered.deliveredAt().plus(WINDOW);
        boolean open = LocalDateTime.now().isBefore(closes);
        return new Status(orderId, open, open ? null : "Deliveries can be rated for 14 days", firstName(delivered.driverId()),
                delivered.deliveredAt(), closes, rating, comment);
    }

    @Transactional
    public Status rate(String customerId, String orderId, Submit submit) {
        if (submit == null || submit.rating() == null || submit.rating() < 1 || submit.rating() > 5) {
            throw new InvalidOrderException("Choose 1 to 5 stars");
        }
        String comment = submit.comment() == null ? null : submit.comment().strip();
        if (comment != null && comment.isEmpty()) {
            comment = null;
        }
        if (comment != null && comment.length() > 500) {
            throw new InvalidOrderException("The comment can be at most 500 characters");
        }
        Status status = status(customerId, orderId);
        if (!status.canRate()) {
            throw new InvalidOrderStateException(status.reason());
        }
        Delivered delivered = delivered(orderId);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                insert into delivery_ratings (order_id, assignment_id, delivery_user_id, customer_id, rating, comment, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (order_id) do update set rating = excluded.rating, comment = excluded.comment, updated_at = excluded.updated_at""",
                orderId, delivered.assignmentId(), delivered.driverId(), customerId, submit.rating(), comment, now, now);
        return status(customerId, orderId);
    }

    /** The newest comments a driver received, without who wrote them. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentComments(String driverId, int limit) {
        return jdbc.queryForList("select rating, comment, updated_at as \"ratedAt\" from delivery_ratings "
                + "where delivery_user_id = ? and comment is not null order by updated_at desc limit ?", driverId, limit);
    }

    private Order own(String customerId, String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!customerId.equals(order.getCustomerId())) {
            // Someone else's order does not exist, as far as this customer can tell
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    private Delivered delivered(String orderId) {
        return jdbc.query("select assignment_id, delivery_user_id, delivered_at from delivery_assignments "
                        + "where order_id = ? and status = 'DELIVERED' order by attempt_number desc limit 1",
                (rs, n) -> new Delivered(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDateTime.class)), orderId)
                .stream().findFirst().orElse(null);
    }

    private String firstName(String driverId) {
        return users.findById(driverId).map(AppUser::getFullName).map(n -> n.strip().split("\\s+")[0]).orElse("Your driver");
    }
}
