package com.example.orders.delivery;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.OrderStatus;
import com.example.orders.service.CustomerNotificationService;
import com.example.orders.service.OrderAccessService;
import com.example.orders.service.OrderEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where our drivers are while a parcel is out for delivery, for the customer's map and the admin's.
 *
 * The driver's phone sends its position every few seconds (POST /api/delivery/location). It is kept only while the
 * driver has an order OUT_FOR_DELIVERY; a customer sees it only for their own order, only during that delivery,
 * and only positions sent since the parcel went out. Each position is pushed to the order's customer straight away
 * (DRIVER_LOCATION, through Kafka so every pod has it). The arrival time is an estimate: PostGIS's distance on the
 * globe (ST_Distance) to the centre of the delivery PIN code, made longer for roads and divided by a city speed.
 *
 * When the driver comes within orders.live-tracking.nearby-m of that centre, the customer is told once per
 * delivery attempt that the driver is in their area. The centre of a PIN area can be kilometres from the house,
 * so it says "in your area", never "at your door".
 */
@Service
public class LiveTrackingService {

    private static final Logger log = LoggerFactory.getLogger(LiveTrackingService.class);

    public static final String EVENT = "DRIVER_LOCATION";

    /** What the phone sends. recordedAt is when it measured the position; null means now. */
    public record Report(Double latitude, Double longitude, Double accuracyM, Double speedMps, Double headingDeg,
                         LocalDateTime recordedAt) {}

    /** @param stored false when it came sooner than orders.live-tracking.min-interval after the last one */
    public record Received(boolean stored, int ordersTracked, long sendEverySeconds) {}

    public record Position(double latitude, double longitude, Double accuracyM, Double headingDeg,
                           LocalDateTime recordedAt, boolean stale) {}

    /** @param areaRadiusM the "in your area" distance around it, so the map can draw the area the alert means */
    public record Destination(double latitude, double longitude, boolean approximate, String label, double areaRadiusM) {}

    /**
     * The customer's map for one order.
     *
     * @param live      the order is out for delivery with one of our drivers; otherwise message says why there is no map
     * @param driver    the driver's last position since the parcel went out, or null before the first one
     * @param destination the centre of the delivery PIN code, or null when the directory has no position for it
     * @param inYourArea  the driver is within nearby-m of that centre now
     */
    public record Tracking(String orderId, OrderStatus status, boolean live, String message, String driverName,
                           Position driver, Destination destination, Double distanceKm, Integer etaMinutes,
                           LocalDateTime etaAt, boolean inYourArea) {}

    /** One driver on the admin's map, with the orders they are carrying. */
    public record DriverOnRoad(String driverId, String name, Position position, List<String> orderIds) {}

    private record OutOrder(String orderId, String customerId, LocalDateTime outSince) {}

    private final JdbcTemplate jdbc;
    private final OrderAccessService orderAccess;
    private final OrderEventBroadcaster broadcaster;
    private final PinDirectory pins;
    private final Clock clock;
    private final Duration minInterval;
    private final double maxAccuracyM;
    private final double speedKmh;
    private final double roadFactor;
    private final Duration staleAfter;
    private final Duration trailKeptFor;
    private final double nearbyM;
    private final CustomerNotificationService notifications;

    public LiveTrackingService(JdbcTemplate jdbc, OrderAccessService orderAccess, OrderEventBroadcaster broadcaster,
                               PinDirectory pins, Clock clock, CustomerNotificationService notifications,
                               @Value("${orders.live-tracking.min-interval:PT10S}") Duration minInterval,
                               @Value("${orders.live-tracking.max-accuracy-m:2000}") double maxAccuracyM,
                               @Value("${orders.live-tracking.city-speed-kmh:20}") double speedKmh,
                               @Value("${orders.live-tracking.road-factor:1.4}") double roadFactor,
                               @Value("${orders.live-tracking.stale-after:PT3M}") Duration staleAfter,
                               @Value("${orders.live-tracking.trail-kept-for:P7D}") Duration trailKeptFor,
                               @Value("${orders.live-tracking.nearby-m:1000}") double nearbyM) {
        this.jdbc = jdbc;
        this.orderAccess = orderAccess;
        this.broadcaster = broadcaster;
        this.pins = pins;
        this.clock = clock;
        this.minInterval = minInterval;
        this.maxAccuracyM = maxAccuracyM;
        this.speedKmh = speedKmh;
        this.roadFactor = roadFactor;
        this.staleAfter = staleAfter;
        this.trailKeptFor = trailKeptFor;
        this.nearbyM = nearbyM;
        this.notifications = notifications;
    }

    // ── The driver's phone ────────────────────────────────────────────────

    /**
     * A position from a driver's phone. Refused unless the driver has a parcel out for delivery; positions that
     * come faster than min-interval are answered but not kept, so a phone sending every second costs nothing.
     */
    @Transactional
    public Received record(String driverId, Report report) {
        Report r = check(report);
        List<OutOrder> out = outFor(driverId);
        if (out.isEmpty()) {
            throw new InvalidOrderStateException("Your location is shared only while you are out for delivery");
        }
        int stored = jdbc.update("insert into driver_locations (driver_id, location, accuracy_m, speed_mps, "
                + "heading_deg, recorded_at, received_at) values (?, " + POINT + ", ?, ?, ?, ?, now()) "
                + "on conflict (driver_id) do update set location = excluded.location, "
                + "accuracy_m = excluded.accuracy_m, speed_mps = excluded.speed_mps, heading_deg = excluded.heading_deg, "
                + "recorded_at = excluded.recorded_at, received_at = excluded.received_at "
                + "where driver_locations.received_at <= now() - make_interval(secs => ?)",
                driverId, r.longitude(), r.latitude(), r.accuracyM(), r.speedMps(), r.headingDeg(), r.recordedAt(),
                (double) minInterval.toMillis() / 1000);
        long every = Math.max(1, minInterval.toSeconds());
        if (stored == 0) {
            return new Received(false, out.size(), every);
        }
        jdbc.update("insert into driver_location_trail (driver_id, location, accuracy_m, recorded_at) "
                + "values (?, " + POINT + ", ?, ?)", driverId, r.longitude(), r.latitude(), r.accuracyM(), r.recordedAt());

        String driverName = firstName(driverId);
        tellCustomersNearby(driverId, driverName, r);
        Position position = new Position(r.latitude(), r.longitude(), r.accuracyM(), r.headingDeg(), r.recordedAt(), false);
        for (OutOrder order : out) {
            Map<String, Object> staff = new LinkedHashMap<>();
            staff.put("orderId", order.orderId());
            staff.put("driverId", driverId);
            staff.put("driverName", driverName);
            staff.put("position", position);
            broadcaster.broadcastForOrder(EVENT, order.orderId(), order.customerId(), staff, view(order.orderId()));
        }
        return new Received(true, out.size(), every);
    }

    // ── The customer's map ────────────────────────────────────────────────

    /** The map for an order the viewer may see (their own, or any for an admin); 404 otherwise. */
    @Transactional(readOnly = true)
    public Tracking tracking(String orderId) {
        if (orderAccess.findVisible(orderId) == null) {
            throw new OrderNotFoundException(orderId);
        }
        return view(orderId);
    }

    private Tracking view(String orderId) {
        Map<String, Object> o = jdbc.queryForMap("select status, status_changed_at, postal_code as pin, city from orders "
                + "where order_id = ?", orderId);
        OrderStatus status = OrderStatus.valueOf((String) o.get("status"));
        if (status != OrderStatus.OUT_FOR_DELIVERY) {
            return new Tracking(orderId, status, false, notLive(status), null, null, null, null, null, null, false);
        }
        List<Map<String, Object>> driver = jdbc.queryForList("select a.delivery_user_id from delivery_assignments a "
                + "where a.order_id = ? and a.status = 'PICKED_UP'", orderId);
        if (driver.isEmpty()) {
            return new Tracking(orderId, status, false, "The courier tracks this delivery", null, null, null, null, null, null, false);
        }
        String driverId = (String) driver.get(0).get("delivery_user_id");
        LocalDateTime outSince = (LocalDateTime) toTime(o.get("status_changed_at"));
        String pin = (String) o.get("pin");
        Destination destination = pins.centre(pin)
                .map(p -> new Destination(p.latitude(), p.longitude(), true,
                        "PIN " + pin + (o.get("city") != null ? ", " + o.get("city") : ""), nearbyM))
                .orElse(null);
        Position position = position(driverId, outSince).orElse(null);
        Double km = null;
        Integer minutes = null;
        LocalDateTime etaAt = null;
        boolean inYourArea = false;
        if (position != null && destination != null) {
            // Metres on the globe, between the driver's last position and the centre of the PIN code
            Double metres = jdbc.queryForObject("select ST_Distance(l.location, p.centre) from driver_locations l, pin_codes p "
                    + "where l.driver_id = ? and p.pin = ?", Double.class, driverId, pin);
            if (metres != null) {
                double straight = metres / 1000;
                km = Math.round(straight * roadFactor * 10) / 10.0;
                minutes = Geo.minutes(straight, roadFactor, speedKmh);
                etaAt = LocalDateTime.now(clock).plusMinutes(minutes).withSecond(0).withNano(0);
                inYourArea = metres <= nearbyM;
            }
        }
        String message = position == null ? "Your parcel is on its way. The map shows the driver once their phone shares a location."
                : position.stale() ? "The driver's phone has not sent a location for a few minutes; this is where they were last."
                : null;
        return new Tracking(orderId, status, true, message, firstName(driverId), position, destination, km, minutes, etaAt,
                inYourArea && position != null && !position.stale());
    }

    // ── The admin's map ───────────────────────────────────────────────────

    /** Drivers with parcels out for delivery, and where they were last seen. */
    @Transactional(readOnly = true)
    public List<DriverOnRoad> driversOnRoad() {
        Map<String, List<String>> orders = new LinkedHashMap<>();
        Map<String, LocalDateTime> since = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("select a.delivery_user_id, a.order_id, o.status_changed_at, u.full_name from delivery_assignments a "
                + "join orders o on o.order_id = a.order_id join app_users u on u.user_id = a.delivery_user_id "
                + "where a.status = 'PICKED_UP' and o.status = 'OUT_FOR_DELIVERY' order by u.full_name, a.order_id", rs -> {
            String driver = rs.getString("delivery_user_id");
            orders.computeIfAbsent(driver, k -> new ArrayList<>()).add(rs.getString("order_id"));
            LocalDateTime out = rs.getObject("status_changed_at", LocalDateTime.class);
            since.merge(driver, out, (a, b) -> a.isBefore(b) ? a : b);
            names.put(driver, rs.getString("full_name"));
        });
        List<DriverOnRoad> drivers = new ArrayList<>();
        orders.forEach((driver, ids) -> drivers.add(new DriverOnRoad(driver, names.get(driver),
                position(driver, since.get(driver)).orElse(null), ids)));
        return drivers;
    }

    // ── Clean-up ──────────────────────────────────────────────────────────

    /** Deletes trail points older than trail-kept-for, and last positions nobody has sent for a day. */
    public int cleanUp() {
        int trail = jdbc.update("delete from driver_location_trail where received_at < now() - make_interval(secs => ?)",
                (double) trailKeptFor.toSeconds());
        int last = jdbc.update("delete from driver_locations where received_at < now() - interval '1 day'");
        if (trail + last > 0) {
            log.info("[TRACKING] Deleted {} trail point(s) and {} old last position(s)", trail, last);
        }
        return trail;
    }

    // ── "Your driver is in your area" ─────────────────────────────────────

    /**
     * Tells each customer whose delivery area the driver has just entered, once per delivery attempt: the
     * assignment is marked in the same statement that finds it, so two positions at once cannot both tell them.
     */
    private void tellCustomersNearby(String driverId, String driverName, Report r) {
        List<Map<String, Object>> reached = jdbc.queryForList("update delivery_assignments a set nearby_notified_at = now() "
                + "from orders o, pin_codes p where a.order_id = o.order_id and p.pin = o.postal_code "
                + "and a.delivery_user_id = ? and a.status = 'PICKED_UP' and o.status = 'OUT_FOR_DELIVERY' "
                + "and a.nearby_notified_at is null and p.centre is not null "
                + "and ST_DWithin(p.centre, " + POINT + ", ?) returning o.order_id, o.customer_id",
                driverId, r.longitude(), r.latitude(), nearbyM);
        for (Map<String, Object> row : reached) {
            String orderId = (String) row.get("order_id");
            notifications.queue(new CustomerNotification(orderId, (String) row.get("customer_id"),
                    driverName + " is in your area with order " + orderId,
                    driverName + " is close to your area with your order " + orderId + " and should reach you soon. "
                            + "Please keep your phone nearby and the delivery code ready. "
                            + "You can follow them on the map on your order page."));
            log.info("[TRACKING] {} is in the delivery area of order {}", driverId, orderId);
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────

    /** A point from two parameters, longitude then latitude (PostGIS's order), on the WGS 84 globe phones use. */
    private static final String POINT = "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography";

    private Report check(Report r) {
        if (r == null || r.latitude() == null || r.longitude() == null) {
            throw new InvalidOrderException("Send latitude and longitude");
        }
        if (!Double.isFinite(r.latitude()) || !Double.isFinite(r.longitude()) || !PinDirectory.inIndia(r.latitude(), r.longitude())) {
            throw new InvalidOrderException("That location is outside India");
        }
        if (r.accuracyM() != null && (r.accuracyM() < 0 || r.accuracyM() > maxAccuracyM)) {
            throw new InvalidOrderException("The location is too rough (more than " + (int) maxAccuracyM + " m off). "
                    + "Turn on precise location on the phone.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime at = r.recordedAt() == null ? now : r.recordedAt();
        if (at.isAfter(now.plusMinutes(2)) || at.isBefore(now.minusMinutes(10))) {
            // A phone with a wrong clock, or a position held back for long: not where the driver is now
            at = now;
        }
        Double speed = r.speedMps() != null && r.speedMps() >= 0 && r.speedMps() < 100 ? r.speedMps() : null;
        Double heading = r.headingDeg() != null && r.headingDeg() >= 0 && r.headingDeg() < 360 ? r.headingDeg() : null;
        return new Report(round(r.latitude()), round(r.longitude()), r.accuracyM(), speed, heading, at);
    }

    private List<OutOrder> outFor(String driverId) {
        return jdbc.query("select a.order_id, o.customer_id, o.status_changed_at from delivery_assignments a "
                + "join orders o on o.order_id = a.order_id where a.delivery_user_id = ? and a.status = 'PICKED_UP' "
                + "and o.status = 'OUT_FOR_DELIVERY' order by a.order_id", (rs, n) -> new OutOrder(rs.getString("order_id"),
                rs.getString("customer_id"), rs.getObject("status_changed_at", LocalDateTime.class)), driverId);
    }

    /** The driver's last position, if it was sent since `since` (when the parcel went out; a minute's grace). */
    private Optional<Position> position(String driverId, LocalDateTime since) {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(staleAfter);
        return jdbc.query("select ST_Y(location::geometry) as latitude, ST_X(location::geometry) as longitude, accuracy_m, "
                + "heading_deg, recorded_at, received_at from driver_locations where driver_id = ? and received_at >= ?", (rs, n) -> new Position(rs.getDouble("latitude"),
                rs.getDouble("longitude"), (Double) number(rs.getObject("accuracy_m")), (Double) number(rs.getObject("heading_deg")),
                rs.getObject("recorded_at", LocalDateTime.class),
                rs.getObject("received_at", LocalDateTime.class).isBefore(staleBefore)),
                driverId, since == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : since.minusMinutes(1)).stream().findFirst();
    }

    private String firstName(String userId) {
        List<String> names = jdbc.queryForList("select full_name from app_users where user_id = ?", String.class, userId);
        if (names.isEmpty() || names.get(0) == null || names.get(0).isBlank()) {
            return "Your driver";
        }
        return names.get(0).strip().split("\\s+")[0];
    }

    private static String notLive(OrderStatus status) {
        return switch (status) {
            case DELIVERED -> "Delivered. Live tracking has ended.";
            case DELIVERY_FAILED -> "The delivery did not go through. Live tracking has ended.";
            case CANCELLED, RETURNED -> "This order is not being delivered.";
            default -> "Live tracking starts when your order is out for delivery.";
        };
    }

    private static Object toTime(Object value) {
        return value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : value;
    }

    private static Object number(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static double round(double degrees) {
        return Math.round(degrees * 1_000_000d) / 1_000_000d;
    }
}
