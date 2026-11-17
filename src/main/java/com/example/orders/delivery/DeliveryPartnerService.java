package com.example.orders.delivery;

import com.example.orders.model.AppUser;
import com.example.orders.model.Order;
import com.example.orders.model.UserProfile;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.UserProfileRepository;
import com.example.orders.service.OrderAccessService;
import com.example.orders.service.ProfileService;
import com.example.orders.storage.ImageStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Who is bringing the customer's order, as delivery apps show it: the driver's first name, photo,
 * vehicle and rating. Shown from pickup until the rating window after delivery closes, and only to
 * the order's customer (and admins). The driver's phone number and full name are never shown.
 */
@Service
public class DeliveryPartnerService {

    public enum Stage { ON_THE_WAY, DELIVERED }

    public record Partner(String name, Stage stage, boolean hasPhoto, String photoVersion,
                          UserProfile.VehicleType vehicleType, String vehicleNumber,
                          BigDecimal rating, long ratingCount, long deliveries,
                          LocalDateTime pickedUpAt, LocalDateTime deliveredAt) {}

    /** partner is null while nobody has picked the order up, or once the window has passed. */
    public record View(String orderId, Partner partner) {}

    private record Attempt(String driverId, String status, LocalDateTime pickedUpAt, LocalDateTime deliveredAt) {}

    private final JdbcTemplate jdbc;
    private final OrderAccessService orderAccess;
    private final AppUserRepository users;
    private final UserProfileRepository profiles;
    private final ProfileService profileService;

    public DeliveryPartnerService(JdbcTemplate jdbc, OrderAccessService orderAccess, AppUserRepository users,
                                  UserProfileRepository profiles, ProfileService profileService) {
        this.jdbc = jdbc;
        this.orderAccess = orderAccess;
        this.users = users;
        this.profiles = profiles;
        this.profileService = profileService;
    }

    @Transactional(readOnly = true)
    public View partner(String orderId) {
        Attempt attempt = attempt(orderId);
        if (attempt == null) {
            return new View(orderId, null);
        }
        AppUser driver = users.findById(attempt.driverId()).orElse(null);
        if (driver == null) {
            return new View(orderId, null);
        }
        UserProfile profile = profiles.findById(driver.getUserId()).orElse(null);
        boolean hasPhoto = profile != null && profile.getPhotoKey() != null;
        Object[] rating = jdbc.queryForObject(
                "select avg(rating), count(*) from delivery_ratings where delivery_user_id = ?",
                (rs, n) -> new Object[] {rs.getBigDecimal(1), rs.getLong(2)}, driver.getUserId());
        long deliveries = jdbc.queryForObject(
                "select count(*) from delivery_assignments where delivery_user_id = ? and status = 'DELIVERED'",
                Long.class, driver.getUserId());
        BigDecimal average = rating[0] == null ? null : ((BigDecimal) rating[0]).setScale(1, RoundingMode.HALF_UP);
        return new View(orderId, new Partner(shortName(driver.getFullName()),
                "DELIVERED".equals(attempt.status()) ? Stage.DELIVERED : Stage.ON_THE_WAY,
                hasPhoto, hasPhoto ? String.valueOf(profile.getPhotoUpdatedAt()) : null,
                profile == null ? null : profile.getVehicleType(),
                profile == null ? null : profile.getVehicleNumber(),
                average, (Long) rating[1], deliveries, attempt.pickedUpAt(), attempt.deliveredAt()));
    }

    /** The photo of the driver bringing this order; null when there is none to show this viewer. */
    @Transactional(readOnly = true)
    public ImageStorage.StoredImage photo(String orderId) {
        Attempt attempt = attempt(orderId);
        return attempt == null ? null : profileService.storedPhoto(attempt.driverId());
    }

    /**
     * The attempt the customer can see: picked up and on its way, or delivered within the rating
     * window. Null for someone else's order, before pickup, or after a failed attempt.
     *
     * @throws com.example.orders.exception.OrderNotFoundException when the viewer may not see the order
     */
    private Attempt attempt(String orderId) {
        Order order = orderAccess.findVisible(orderId);
        if (order == null) {
            throw new com.example.orders.exception.OrderNotFoundException(orderId);
        }
        Attempt latest = jdbc.query("select delivery_user_id, status, picked_up_at, delivered_at from delivery_assignments "
                        + "where order_id = ? and status in ('PICKED_UP', 'DELIVERED') order by attempt_number desc limit 1",
                (rs, n) -> new Attempt(rs.getString(1), rs.getString(2),
                        rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class)), orderId)
                .stream().findFirst().orElse(null);
        if (latest == null) {
            return null;
        }
        if ("DELIVERED".equals(latest.status())) {
            boolean recent = latest.deliveredAt() != null
                    && LocalDateTime.now().isBefore(latest.deliveredAt().plus(DeliveryRatingService.WINDOW));
            return recent ? latest : null;
        }
        // Picked up, but the order has since moved on (a failed attempt is closed as FAILED, not PICKED_UP)
        return latest;
    }

    /** "Ravi Kumar Shetty" → "Ravi S.": enough to greet them at the door. */
    static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Your delivery partner";
        }
        String[] parts = fullName.strip().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + Character.toUpperCase(parts[parts.length - 1].charAt(0)) + ".";
    }
}
