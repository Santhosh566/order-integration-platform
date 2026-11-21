package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.storage.ImageProcessor;
import com.example.orders.storage.ImageStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Promotions: the banners at the top of the shop. Each opens a product, a category, or the products a
 * coupon is for, so an ad for "20% off pens with PENS20" takes the shopper straight to those pens.
 *
 * A banner shows while it is on, inside its dates, and while what it points at can be bought: a product
 * taken off sale, a hidden category or a coupon that ended or was used up takes its banners down by
 * itself, so the shop never advertises something that cannot be had.
 *
 * Views and clicks are counted from signed-in shoppers, for the click-through rate on the admin page.
 */
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    /** At most this many banners are shown at once. */
    public static final int MAX_SHOWN = 8;

    public enum TargetType { PRODUCT, CATEGORY, COUPON }

    public enum Theme { BLUE, GREEN, ORANGE, GRAPE, RED, DARK }

    /**
     * LIVE: shown now. SCHEDULED: starts later. ENDED: past its end. OFF: switched off.
     * UNAVAILABLE: on and in its dates, but what it points at cannot be bought (see unavailableReason).
     */
    public enum Status { LIVE, SCHEDULED, ENDED, OFF, UNAVAILABLE }

    /** What an admin sends to create or change a promotion. Exactly one of sku, categoryId, couponCode, as targetType says. */
    public record PromotionRequest(String title, String message, String callToAction, Theme theme, String imageAlt,
                                   TargetType targetType, String sku, Long categoryId, String couponCode,
                                   LocalDateTime startsAt, LocalDateTime endsAt, Integer priority, Boolean active) {}

    /** A promotion as admins see it, with its counts. */
    public record Promotion(long id, String title, String message, String callToAction, Theme theme, String imageKey,
                            String imageAlt, TargetType targetType, String sku, String productName, Long categoryId,
                            String categoryName, String categorySlug, String couponCode, LocalDateTime startsAt,
                            LocalDateTime endsAt, int priority, boolean active, long impressions, long clicks,
                            LocalDateTime createdAt, String createdBy, LocalDateTime updatedAt, String updatedBy,
                            Status status, String unavailableReason) {}

    /**
     * A banner as the shop shows it.
     *
     * @param offer     for a coupon banner: the coupon's terms
     * @param appliesTo for a coupon banner: names of the products and categories it is for; empty for any order
     */
    public record Banner(long id, String title, String message, String callToAction, Theme theme, String imageKey,
                         String imageAlt, TargetType targetType, String sku, String productName, String categorySlug,
                         String categoryName, String couponCode, ProductOffers.ProductOffer offer,
                         List<String> appliesTo) {}

    private final JdbcTemplate jdbc;
    private final CheckoutService checkout;
    private final ImageStorage storage;
    private final ImageProcessor processor;
    private final long maxUploadBytes;

    public PromotionService(JdbcTemplate jdbc, CheckoutService checkout, ImageStorage storage, ImageProcessor processor,
                            @Value("${orders.storage.max-upload-bytes}") long maxUploadBytes) {
        this.jdbc = jdbc;
        this.checkout = checkout;
        this.storage = storage;
        this.processor = processor;
        this.maxUploadBytes = maxUploadBytes;
    }

    // ── For admins ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Promotion> list() {
        Map<String, CheckoutService.Coupon> live = liveCoupons();
        return jdbc.query(SELECT + "order by p.active desc, p.priority desc, p.created_at desc", (rs, n) -> toPromotion(rs, live));
    }

    @Transactional(readOnly = true)
    public Promotion get(long id) {
        List<Promotion> found = jdbc.query(SELECT + "where p.promotion_id = ?", (rs, n) -> toPromotion(rs, liveCoupons()), id);
        if (found.isEmpty()) {
            throw new NotFoundException("No promotion " + id);
        }
        return found.get(0);
    }

    @Transactional
    public Promotion create(PromotionRequest request, String admin) {
        Checked c = check(request, true);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("insert into promotions (title, message, call_to_action, "
                    + "theme, image_alt, target_type, sku, category_id, coupon_code, starts_at, ends_at, priority, active, "
                    + "created_by, updated_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            bind(ps, c);
            ps.setString(14, admin);
            ps.setString(15, admin);
            return ps;
        }, key);
        long id = ((Number) key.getKeys().get("promotion_id")).longValue();
        log.info("[PROMOTIONS] {} created promotion {} \"{}\" for {} {}", admin, id, c.title(), c.type(), c.targetKey());
        return get(id);
    }

    /** Replaces every field; the picture and the counts stay. */
    @Transactional
    public Promotion update(long id, PromotionRequest request, String admin) {
        lock(id);
        Checked c = check(request, false);
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("update promotions set title = ?, message = ?, "
                    + "call_to_action = ?, theme = ?, image_alt = ?, target_type = ?, sku = ?, category_id = ?, "
                    + "coupon_code = ?, starts_at = ?, ends_at = ?, priority = ?, active = ?, updated_by = ?, "
                    + "updated_at = now() where promotion_id = ?");
            bind(ps, c);
            ps.setString(14, admin);
            ps.setLong(15, id);
            return ps;
        });
        log.info("[PROMOTIONS] {} changed promotion {}", admin, id);
        return get(id);
    }

    /** Turns it on or off without touching anything else. */
    @Transactional
    public Promotion setActive(long id, boolean active, String admin) {
        lock(id);
        jdbc.update("update promotions set active = ?, updated_by = ?, updated_at = now() where promotion_id = ?", active, admin, id);
        return get(id);
    }

    @Transactional
    public void delete(long id, String admin) {
        String oldKey = lock(id);
        jdbc.update("delete from promotions where promotion_id = ?", id);
        removeAfterCommit(oldKey);
        log.info("[PROMOTIONS] {} deleted promotion {}", admin, id);
    }

    /** A new banner picture; the old one is removed once the change is committed. */
    @Transactional
    public Promotion setImage(long id, byte[] upload, String admin) {
        String oldKey = lock(id);
        if (upload == null || upload.length == 0) {
            throw new InvalidOrderException("Choose a picture to upload");
        }
        if (upload.length > maxUploadBytes) {
            throw new InvalidOrderException("The picture is " + upload.length / 1024 / 1024 + " MB; at most "
                    + maxUploadBytes / 1024 / 1024 + " MB");
        }
        byte[] banner = processor.banner(upload);
        String newKey = "promotions/" + id + "/" + UUID.randomUUID() + "-" + ImageProcessor.BANNER_WIDTH + ".jpg";
        storage.put(newKey, banner, "image/jpeg");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // The new picture if the change failed, the old one if it worked
                String unused = status == STATUS_COMMITTED ? oldKey : newKey;
                if (unused != null) {
                    removeQuietly(unused);
                }
            }
        });
        jdbc.update("update promotions set image_key = ?, updated_by = ?, updated_at = now() where promotion_id = ?",
                newKey, admin, id);
        return get(id);
    }

    @Transactional
    public Promotion removeImage(long id, String admin) {
        String oldKey = lock(id);
        jdbc.update("update promotions set image_key = null, updated_by = ?, updated_at = now() where promotion_id = ?", admin, id);
        removeAfterCommit(oldKey);
        return get(id);
    }

    // ── For the shop ───────────────────────────────────────────────────────

    /** The banners to show now, highest priority first. */
    @Transactional(readOnly = true)
    public List<Banner> banners() {
        Map<String, CheckoutService.Coupon> live = liveCoupons();
        return jdbc.query(SELECT + "where p.active and (p.starts_at is null or p.starts_at <= now()) "
                        + "and (p.ends_at is null or p.ends_at > now()) order by p.priority desc, p.created_at desc",
                        (rs, n) -> toPromotion(rs, live)).stream()
                .filter(p -> p.status() == Status.LIVE)
                .limit(MAX_SHOWN)
                .map(p -> {
                    CheckoutService.Coupon coupon = p.couponCode() == null ? null : live.get(p.couponCode());
                    return new Banner(p.id(), p.title(), p.message(), p.callToAction(), p.theme(), p.imageKey(),
                            p.imageAlt(), p.targetType(), p.sku(), p.productName(), p.categorySlug(), p.categoryName(),
                            p.couponCode(), coupon == null ? null : ProductOffers.ProductOffer.of(coupon),
                            coupon == null ? List.of() : coupon.appliesTo().stream().map(CouponTargets.Target::name).toList());
                })
                .toList();
    }

    /** A shopper saw the banner. Counted in the database, so every server adds to the same number. */
    public void viewed(long id) {
        count("impressions", id);
    }

    /** A shopper opened the banner. */
    public void clicked(long id) {
        count("clicks", id);
    }

    /** Only keys of promotions are served from promotions/, so nothing else in the bucket can be read. */
    @Transactional(readOnly = true)
    public boolean isKnownImage(String key) {
        Integer n = jdbc.queryForObject("select count(*) from promotions where image_key = ?", Integer.class, key);
        return n != null && n > 0;
    }

    // ── Inside ─────────────────────────────────────────────────────────────

    private record Checked(String title, String message, String callToAction, Theme theme, String imageAlt,
                           TargetType type, String sku, Long categoryId, String couponCode, LocalDateTime startsAt,
                           LocalDateTime endsAt, int priority, boolean active) {
        String targetKey() {
            return sku != null ? sku : couponCode != null ? couponCode : String.valueOf(categoryId);
        }
    }

    private Checked check(PromotionRequest r, boolean creating) {
        if (r == null) {
            throw new InvalidOrderException("Promotion details are required");
        }
        String title = text(r.title(), 80, "The title");
        if (title == null || title.length() < 3) {
            throw new InvalidOrderException("Give a title of 3 to 80 characters; it is the banner's headline");
        }
        String message = text(r.message(), 200, "The message");
        String cta = text(r.callToAction(), 30, "The button text");
        String alt = text(r.imageAlt(), 200, "The picture description");
        if (r.targetType() == null) {
            throw new InvalidOrderException("Choose what the banner opens: a product, a category or a coupon");
        }
        String sku = null;
        Long categoryId = null;
        String coupon = null;
        switch (r.targetType()) {
            case PRODUCT -> {
                sku = r.sku() == null ? "" : r.sku().strip();
                if (count("select count(*) from inventory where sku = ?", sku) == 0) {
                    throw new InvalidOrderException("No product " + (sku.isEmpty() ? "chosen" : sku));
                }
            }
            case CATEGORY -> {
                categoryId = r.categoryId();
                if (categoryId == null || count("select count(*) from categories where category_id = ?", categoryId) == 0) {
                    throw new InvalidOrderException("No category " + (categoryId == null ? "chosen" : categoryId));
                }
            }
            case COUPON -> {
                coupon = r.couponCode() == null ? "" : r.couponCode().strip().toUpperCase(Locale.ROOT);
                if (count("select count(*) from coupons where code = ? and customer_id is null", coupon) == 0) {
                    throw new InvalidOrderException("No coupon " + (coupon.isEmpty() ? "chosen" : coupon));
                }
            }
        }
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            throw new InvalidOrderException("The end must be after the start");
        }
        if (creating && r.endsAt() != null && !r.endsAt().isAfter(LocalDateTime.now())) {
            throw new InvalidOrderException("The end is already past");
        }
        int priority = r.priority() == null ? 0 : r.priority();
        if (priority < 0 || priority > 100) {
            throw new InvalidOrderException("The priority is 0 to 100; higher shows first");
        }
        return new Checked(title, message, cta, r.theme() == null ? Theme.BLUE : r.theme(), alt, r.targetType(), sku,
                categoryId, coupon, r.startsAt(), r.endsAt(), priority, r.active() == null || r.active());
    }

    private static void bind(PreparedStatement ps, Checked c) throws SQLException {
        ps.setString(1, c.title());
        ps.setString(2, c.message());
        ps.setString(3, c.callToAction());
        ps.setString(4, c.theme().name());
        ps.setString(5, c.imageAlt());
        ps.setString(6, c.type().name());
        ps.setString(7, c.sku());
        ps.setObject(8, c.categoryId());
        ps.setString(9, c.couponCode());
        ps.setObject(10, c.startsAt());
        ps.setObject(11, c.endsAt());
        ps.setInt(12, c.priority());
        ps.setBoolean(13, c.active());
    }

    /** Locks the row for the change and returns its picture's key. */
    private String lock(long id) {
        List<String> found = jdbc.query("select image_key from promotions where promotion_id = ? for update",
                (rs, n) -> rs.getString(1), id);
        if (found.isEmpty()) {
            throw new NotFoundException("No promotion " + id);
        }
        return found.get(0);
    }

    private void count(String column, long id) {
        // column is one of two constants, never input
        if (jdbc.update("update promotions set " + column + " = " + column + " + 1 where promotion_id = ?", id) == 0) {
            throw new NotFoundException("No promotion " + id);
        }
    }

    private Map<String, CheckoutService.Coupon> liveCoupons() {
        return checkout.liveCoupons().stream().collect(Collectors.toMap(CheckoutService.Coupon::code, Function.identity()));
    }

    private Promotion toPromotion(ResultSet rs, Map<String, CheckoutService.Coupon> liveCoupons) throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startsAt = time(rs.getTimestamp("starts_at"));
        LocalDateTime endsAt = time(rs.getTimestamp("ends_at"));
        boolean active = rs.getBoolean("active");
        TargetType type = TargetType.valueOf(rs.getString("target_type"));
        String unavailable = switch (type) {
            case PRODUCT -> rs.getBoolean("product_on_sale") ? null : "The product is not on sale";
            case CATEGORY -> rs.getBoolean("category_shown") ? null : "The category is hidden";
            case COUPON -> liveCoupons.containsKey(rs.getString("coupon_code")) ? null
                    : "The coupon is off, outside its dates or used up";
        };
        Status status = !active ? Status.OFF
                : endsAt != null && !now.isBefore(endsAt) ? Status.ENDED
                : startsAt != null && now.isBefore(startsAt) ? Status.SCHEDULED
                : unavailable != null ? Status.UNAVAILABLE
                : Status.LIVE;
        Object category = rs.getObject("category_id");
        return new Promotion(rs.getLong("promotion_id"), rs.getString("title"), rs.getString("message"),
                rs.getString("call_to_action"), Theme.valueOf(rs.getString("theme")), rs.getString("image_key"),
                rs.getString("image_alt"), type, rs.getString("sku"), rs.getString("product_name"),
                category == null ? null : ((Number) category).longValue(), rs.getString("category_name"),
                rs.getString("category_slug"), rs.getString("coupon_code"), startsAt, endsAt, rs.getInt("priority"),
                active, rs.getLong("impressions"), rs.getLong("clicks"), time(rs.getTimestamp("created_at")),
                rs.getString("created_by"), time(rs.getTimestamp("updated_at")), rs.getString("updated_by"),
                status, status == Status.UNAVAILABLE ? unavailable : null);
    }

    private static final String SELECT = "select p.*, i.product_name, "
            + "(i.sku is not null and i.active and i.unit_price is not null) as product_on_sale, "
            + "c.name as category_name, c.slug as category_slug, "
            + "(c.category_id is not null and c.active and (pc.category_id is null or pc.active)) as category_shown "
            + "from promotions p left join inventory i on i.sku = p.sku "
            + "left join categories c on c.category_id = p.category_id "
            + "left join categories pc on pc.category_id = c.parent_id ";

    private int count(String sql, Object arg) {
        Integer n = jdbc.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    private static String text(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        if (clean.length() > max) {
            throw new InvalidOrderException(what + " can be at most " + max + " characters");
        }
        return clean;
    }

    private void removeAfterCommit(String key) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                removeQuietly(key);
            }
        });
    }

    private void removeQuietly(String key) {
        try {
            storage.remove(key);
        } catch (RuntimeException e) {
            log.warn("[PROMOTIONS] Left {} in storage: {}", key, e.getMessage());
        }
    }

    private static LocalDateTime time(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
