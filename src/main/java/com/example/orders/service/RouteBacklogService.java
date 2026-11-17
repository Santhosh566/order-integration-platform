package com.example.orders.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * How much work is waiting at each consumer route, so an operator sees what a pause holds back.
 * Each count uses the same condition the consumer uses to pick its work up.
 */
@Service
public class RouteBacklogService {

    private final JdbcTemplate jdbc;
    private final Path inboxDir;
    private final boolean simulateDelivery;
    private final long stuckAfterMs;

    public RouteBacklogService(JdbcTemplate jdbc,
                               @Value("${orders.inbox-dir}") String inboxDir,
                               @Value("${orders.delivery.simulate}") boolean simulateDelivery,
                               @Value("${orders.recovery.stuck-after-ms}") long stuckAfterMs) {
        this.jdbc = jdbc;
        this.inboxDir = Path.of(inboxDir);
        this.simulateDelivery = simulateDelivery;
        this.stuckAfterMs = stuckAfterMs;
    }

    /**
     * @return {count, unit, orderStatus (the order list to open, if any), note}, or null for a
     *         route that keeps no queue of its own
     */
    public Map<String, Object> backlogFor(String routeId) {
        return switch (routeId) {
            case "outbox-poller" -> backlog(count(
                    "select count(*) from outbox_events where status <> 'PUBLISHED'"),
                    "orders not yet sent to Kafka", "PENDING", null);
            // Sent to Kafka but not taken up by the pipeline yet (or being worked on now)
            case "order-event-consumer" -> backlog(count(
                    "select count(*) from orders o where o.status = 'PENDING' and o.deleted_at is null "
                    + "and not exists (select 1 from outbox_events e where e.order_id = o.order_id and e.status <> 'PUBLISHED')"),
                    "orders waiting in Kafka or being processed", "PENDING", null);
            case "customer-notification-outbox" -> backlog(count(
                    "select count(*) from customer_notifications where status in ('PENDING', 'SENDING')"),
                    "emails waiting", null, null);
            case "b2b-csv-file-watcher" -> backlog(files("csv"), "files waiting in " + inboxDir.resolve("csv"), null, null);
            case "supplier-xml-file-watcher" -> backlog(files("xml"), "files waiting in " + inboxDir.resolve("xml"), null, null);
            case "carrier-tracking-poller" -> simulateDelivery
                    ? backlog(count("select count(*) from orders where deleted_at is null "
                            + "and status in ('READY_FOR_DISPATCH', 'IN_TRANSIT', 'OUT_FOR_DELIVERY')"),
                            "orders on the road", "READY_FOR_DISPATCH,IN_TRANSIT,OUT_FOR_DELIVERY", null)
                    : backlog(0, "orders on the road", null,
                            "Delivery simulation is off: drivers move orders, so pausing this changes nothing");
            case "catalog-price-scheduler" -> backlog(count(
                    "select count(*) from product_prices where applied_at is null and cancelled_at is null "
                    + "and effective_from <= now()"),
                    "scheduled prices due", null, null);
            case "stuck-order-recovery" -> backlog(jdbc.queryForObject(
                    "select count(*) from orders where deleted_at is null "
                    + "and status in ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMED') and status_changed_at < ?",
                    Long.class, Timestamp.valueOf(LocalDateTime.now().minusNanos(stuckAfterMs * 1_000_000))),
                    "orders stuck longer than " + stuckAfterMs / 60_000 + " min", "PENDING,INVENTORY_RESERVED,CONFIRMED", null);
            default -> null;
        };
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** Files the watcher would pick up: regular, not hidden (the archive and error folders sit beside, not inside). */
    private long files(String folder) {
        Path dir = inboxDir.resolve(folder);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static Map<String, Object> backlog(long count, String unit, String orderStatus, String note) {
        Map<String, Object> backlog = new LinkedHashMap<>();
        backlog.put("count", count);
        backlog.put("unit", unit);
        backlog.put("orderStatus", orderStatus);
        backlog.put("note", note);
        return backlog;
    }
}
