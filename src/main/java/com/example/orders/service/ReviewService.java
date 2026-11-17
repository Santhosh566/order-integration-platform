package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.model.CustomerNotification;
import com.example.orders.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ratings and reviews. Only a customer who received the product may review it, once per product
 * (editing replaces the review).
 *
 * - A rating without words has nothing to moderate and counts at once.
 * - A review with a title or text waits for an admin; until approved only its author sees it.
 * - Editing an approved review with words sends it back to the queue.
 *
 * The product's average and count (inventory.rating_avg, rating_count) are worked out again from the
 * approved reviews whenever one changes, in the same transaction, so the shop can sort by rating cheaply.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    static final int TITLE_MAX = 120;
    static final int BODY_MAX = 2000;
    static final int NOTE_MAX = 500;

    public enum Status { PENDING, APPROVED, REJECTED }

    public enum Sort { NEWEST, HIGHEST, LOWEST }

    /** What a customer sends. */
    public record Submit(Integer rating, String title, String body) {}

    /** An admin's decision; the note is required to reject and is emailed to the customer. */
    public record Decision(String note) {}

    /**
     * A review as its author or an admin sees it.
     *
     * @param author "Alice S.": the first name and the initial of the last
     */
    public record Review(long id, String sku, String productName, String customerId, String author, String orderId,
                         int rating, String title, String body, Status status, LocalDateTime createdAt,
                         LocalDateTime updatedAt, LocalDateTime decidedAt, String decidedBy, String decisionNote) {}

    /** A review as the shop shows it: approved, without who decided. */
    public record PublicReview(long id, String author, int rating, String title, String body, LocalDateTime createdAt,
                               boolean edited, boolean verifiedPurchase) {}

    public record ReviewPage(List<PublicReview> items, int totalItems, int totalPages, int page, int size) {}

    /** A product the customer received and has not reviewed yet. */
    public record ToReview(String sku, String name, LocalDateTime deliveredAt) {}

    /** The customer's side: their reviews, and what they could review. */
    public record Mine(List<Review> reviews, List<ToReview> toReview) {}

    /** For the product page: may this customer review it, and their review if they did. */
    public record ForProduct(boolean canReview, Review review) {}

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final CustomerNotificationService notifications;

    public ReviewService(JdbcTemplate jdbc, CurrentUser currentUser, CustomerNotificationService notifications) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    // ── Customers ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Mine mine() {
        String customer = customerId();
        List<Review> reviews = jdbc.query(SELECT + "where r.customer_id = ? order by coalesce(r.updated_at, r.created_at) desc",
                REVIEW, customer);
        List<ToReview> toReview = jdbc.query("select i.sku, max(i.product_name) as name, max(o.delivered_at) as delivered_at "
                + "from orders o join order_items i on i.order_id = o.order_id "
                + "where o.customer_id = ? and o.status = 'DELIVERED' and o.deleted_at is null "
                + "and not exists (select 1 from reviews r where r.sku = i.sku and r.customer_id = o.customer_id) "
                + "and exists (select 1 from inventory p where p.sku = i.sku) "
                + "group by i.sku order by max(o.delivered_at) desc nulls last limit 50",
                (rs, n) -> new ToReview(rs.getString("sku"), rs.getString("name"),
                        rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toLocalDateTime()),
                customer);
        return new Mine(reviews, toReview);
    }

    @Transactional(readOnly = true)
    public ForProduct forProduct(String sku) {
        String customer = customerId();
        List<Review> own = jdbc.query(SELECT + "where r.sku = ? and r.customer_id = ?", REVIEW, sku, customer);
        return new ForProduct(own.isEmpty() ? deliveredOrder(customer, sku) != null : true,
                own.isEmpty() ? null : own.get(0));
    }

    /**
     * Writes or replaces the customer's review of a product they received.
     */
    @Transactional
    public Review submit(String sku, Submit request) {
        String customer = customerId();
        if (request == null || request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new InvalidOrderException("Choose a rating from 1 to 5 stars");
        }
        String title = clean(request.title(), TITLE_MAX, "The title");
        String body = clean(request.body(), BODY_MAX, "The review");
        // Locks the product row: two reviews of one product cannot work out its average at the same time
        List<String> product = jdbc.queryForList("select sku from inventory where sku = ? for update", String.class, sku);
        if (product.isEmpty()) {
            throw new NotFoundException("No product " + sku);
        }
        String orderId = deliveredOrder(customer, sku);
        if (orderId == null) {
            throw new InvalidOrderStateException("You can review a product once it has been delivered to you");
        }
        Status status = title == null && body == null ? Status.APPROVED : Status.PENDING;
        int updated = jdbc.update("update reviews set rating = ?, title = ?, body = ?, status = ?, updated_at = now(), "
                + "decided_at = null, decided_by = null, decision_note = null where sku = ? and customer_id = ?",
                request.rating(), title, body, status.name(), sku, customer);
        if (updated == 0) {
            jdbc.update("insert into reviews (sku, customer_id, order_id, rating, title, body, status) values (?, ?, ?, ?, ?, ?, ?)",
                    sku, customer, orderId, request.rating(), title, body, status.name());
        }
        refreshRating(sku);
        log.info("[REVIEWS] {} rated {} {} star(s): {}", customer, sku, request.rating(), status);
        return jdbc.queryForObject(SELECT + "where r.sku = ? and r.customer_id = ?", REVIEW, sku, customer);
    }

    @Transactional
    public void delete(String sku) {
        jdbc.queryForList("select sku from inventory where sku = ? for update", String.class, sku);
        if (jdbc.update("delete from reviews where sku = ? and customer_id = ?", sku, customerId()) > 0) {
            refreshRating(sku);
        }
    }

    // ── The shop ───────────────────────────────────────────────────────────

    /** Approved reviews with words; ratings alone only count towards the average. */
    @Transactional(readOnly = true)
    public ReviewPage published(String sku, Sort sort, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, 50));
        int pageNo = Math.max(0, page);
        String order = switch (sort == null ? Sort.NEWEST : sort) {
            case NEWEST -> "coalesce(r.updated_at, r.created_at) desc, r.review_id desc";
            case HIGHEST -> "r.rating desc, coalesce(r.updated_at, r.created_at) desc, r.review_id desc";
            case LOWEST -> "r.rating asc, coalesce(r.updated_at, r.created_at) desc, r.review_id desc";
        };
        String where = "where r.sku = ? and r.status = 'APPROVED' and (r.title is not null or r.body is not null) ";
        Integer total = jdbc.queryForObject("select count(*) from reviews r " + where, Integer.class, sku);
        int totalItems = total == null ? 0 : total;
        List<PublicReview> items = jdbc.query(SELECT + where + "order by " + order + " limit ? offset ?",
                (rs, n) -> {
                    Review r = REVIEW.mapRow(rs, n);
                    return new PublicReview(r.id(), r.author(), r.rating(), r.title(), r.body(), r.createdAt(),
                            r.updatedAt() != null, true);
                }, sku, pageSize, pageNo * pageSize);
        return new ReviewPage(items, totalItems, (totalItems + pageSize - 1) / pageSize, pageNo, pageSize);
    }

    /** How many approved ratings gave 1, 2, 3, 4 and 5 stars, in that order; and how many have words. */
    public record Breakdown(List<Integer> stars, int reviewCount) {}

    @Transactional(readOnly = true)
    public Breakdown breakdown(String sku) {
        int[] stars = new int[5];
        int[] withWords = new int[1];
        jdbc.query("select rating, count(*) as n, count(*) filter (where title is not null or body is not null) as words "
                + "from reviews where sku = ? and status = 'APPROVED' group by rating", rs -> {
            stars[rs.getInt("rating") - 1] = rs.getInt("n");
            withWords[0] += rs.getInt("words");
        }, sku);
        List<Integer> counts = new ArrayList<>();
        for (int s : stars) {
            counts.add(s);
        }
        return new Breakdown(counts, withWords[0]);
    }

    // ── Admins ─────────────────────────────────────────────────────────────

    /** The moderation queue (oldest first) or a status's history (newest first); at most 200. */
    @Transactional(readOnly = true)
    public List<Review> byStatus(Status status) {
        Status wanted = status == null ? Status.PENDING : status;
        String order = wanted == Status.PENDING ? "coalesce(r.updated_at, r.created_at) asc" : "coalesce(r.decided_at, r.created_at) desc";
        return jdbc.query(SELECT + "where r.status = ? order by " + order + " limit 200", REVIEW, wanted.name());
    }

    @Transactional(readOnly = true)
    public int waiting() {
        Integer n = jdbc.queryForObject("select count(*) from reviews where status = 'PENDING'", Integer.class);
        return n == null ? 0 : n;
    }

    @Transactional
    public Review approve(long id) {
        return decide(id, Status.APPROVED, null);
    }

    @Transactional
    public Review reject(long id, Decision decision) {
        String note = decision == null ? null : clean(decision.note(), NOTE_MAX, "The note");
        if (note == null) {
            throw new InvalidOrderException("Say why the review is rejected; the customer is told");
        }
        Review review = decide(id, Status.REJECTED, note);
        notifications.queue(new CustomerNotification(null, review.customerId(),
                "Your review of " + review.productName() + " was not published",
                "Thank you for reviewing " + review.productName() + ". We could not publish it: " + note
                        + "\n\nYou can edit your review on the product page and we will look at it again."));
        return review;
    }

    private Review decide(long id, Status status, String note) {
        List<String> skus = jdbc.queryForList("select sku from reviews where review_id = ?", String.class, id);
        if (skus.isEmpty()) {
            throw new NotFoundException("No review " + id);
        }
        String sku = skus.get(0);
        jdbc.queryForList("select sku from inventory where sku = ? for update", String.class, sku);
        // Read again under the product lock: the customer may have edited it meanwhile
        Review current = jdbc.queryForObject(SELECT + "where r.review_id = ?", REVIEW, id);
        if (current.status() != Status.PENDING) {
            throw new InvalidOrderStateException("Review " + id + " was already " + current.status().name().toLowerCase(Locale.ROOT));
        }
        String admin = currentUser.get().map(v -> v.userId()).orElse("system");
        jdbc.update("update reviews set status = ?, decided_at = now(), decided_by = ?, decision_note = ? where review_id = ?",
                status.name(), admin, note, id);
        refreshRating(sku);
        log.info("[REVIEWS] {} {} review {} of {}", admin, status, id, sku);
        return jdbc.queryForObject(SELECT + "where r.review_id = ?", REVIEW, id);
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private void refreshRating(String sku) {
        jdbc.update("update inventory i set rating_avg = coalesce(s.average, 0), rating_count = s.n "
                + "from (select round(avg(rating)::numeric, 2) as average, count(*) as n "
                + "      from reviews where sku = ? and status = 'APPROVED') s where i.sku = ?", sku, sku);
    }

    /** The customer's most recent delivered order holding the product, or null. */
    private String deliveredOrder(String customer, String sku) {
        List<String> ids = jdbc.queryForList("select o.order_id from orders o where o.customer_id = ? and o.status = 'DELIVERED' "
                + "and o.deleted_at is null and exists (select 1 from order_items i where i.order_id = o.order_id and i.sku = ?) "
                + "order by o.delivered_at desc nulls last limit 1", String.class, customer, sku);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String customerId() {
        return currentUser.require().userId();
    }

    private static String clean(String text, int max, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() > max) {
            throw new InvalidOrderException(what + " can be at most " + max + " characters");
        }
        return trimmed;
    }

    private static final String SELECT = "select r.review_id, r.sku, i.product_name, r.customer_id, u.full_name, r.order_id, "
            + "r.rating, r.title, r.body, r.status, r.created_at, r.updated_at, r.decided_at, r.decided_by, r.decision_note "
            + "from reviews r join inventory i on i.sku = r.sku left join app_users u on u.user_id = r.customer_id ";

    private static final RowMapper<Review> REVIEW = (rs, n) -> new Review(rs.getLong("review_id"), rs.getString("sku"),
            rs.getString("product_name"), rs.getString("customer_id"), shortName(rs.getString("full_name")),
            rs.getString("order_id"), rs.getInt("rating"), rs.getString("title"), rs.getString("body"),
            Status.valueOf(rs.getString("status")), time(rs.getTimestamp("created_at")), time(rs.getTimestamp("updated_at")),
            time(rs.getTimestamp("decided_at")), rs.getString("decided_by"), rs.getString("decision_note"));

    private static LocalDateTime time(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    /** "Alice Smith" → "Alice S.": enough to be real, not enough to find them. */
    static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "A customer";
        }
        String[] parts = fullName.strip().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
