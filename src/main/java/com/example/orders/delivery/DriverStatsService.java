package com.example.orders.delivery;

import com.example.orders.model.AppUser;
import com.example.orders.model.Role;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.service.PaymentGatewayService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much a delivery user has done: deliveries and return pickups completed, failed attempts, the
 * tasks they were given and the cash they collected, today, this week, this month and this year.
 *
 * Everything is counted from the delivery and pickup attempts themselves, so nothing extra is
 * recorded. Periods are calendar periods in India time (weeks start on Monday), and each is compared
 * with the same stretch of the period before: Wednesday noon is compared with last Wednesday noon,
 * not with the whole of last week.
 */
@Service
public class DriverStatsService {

    public enum Period { DAY, WEEK, MONTH, YEAR }

    /**
     * @param completed     deliveries + pickups collected
     * @param tasks         deliveries and pickups given to the driver in the period (not those taken back)
     * @param successRate   completed / (completed + failed), or null when there were neither
     * @param cashCollected cash on delivery handed over by the customers
     * @param earnings      what the finished attempts pay (DriverPay), as stored on each
     * @param rating        customers' average rating of the deliveries made in the period, or null
     */
    public record Figures(long delivered, long pickupsCollected, long completed, long failed, long tasks,
                          Double successRate, BigDecimal cashCollected, BigDecimal earnings, Double rating,
                          long ratingCount) {}

    /** A period so far, and the same stretch of the one before. */
    public record PeriodFigures(Period period, LocalDate from, LocalDate to, Figures figures, Figures previous) {}

    public record OpenNow(long deliveries, long pickups) {}

    /** @param feedback the newest customer comments, without who wrote them */
    public record Summary(String driverId, String driverName, OpenNow openNow, Map<Period, PeriodFigures> periods,
                          List<Map<String, Object>> feedback) {}

    /** One bar of the chart: an hour (DAY), a day (WEEK, MONTH) or a month (YEAR). */
    public record Bucket(LocalDateTime start, String label, long delivered, long pickupsCollected, long failed) {}

    public record History(Period period, LocalDate from, LocalDate to, List<Bucket> buckets) {}

    public record DriverRow(String driverId, String driverName, Figures figures) {}

    public record Leaderboard(Period period, LocalDate from, LocalDate to, List<DriverRow> drivers) {}

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final Clock clock;
    private final DeliveryRatingService ratings;

    public DriverStatsService(JdbcTemplate jdbc, AppUserRepository users, Clock clock, DeliveryRatingService ratings) {
        this.ratings = ratings;
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(String driverId) {
        LocalDateTime now = now();
        Map<Period, PeriodFigures> periods = new EnumMap<>(Period.class);
        for (Period period : Period.values()) {
            periods.put(period, periodFigures(driverId, period, now));
        }
        return new Summary(driverId, name(driverId), openNow(driverId), periods, ratings.recentComments(driverId, 5));
    }

    @Transactional(readOnly = true)
    public History history(String driverId, Period period) {
        LocalDateTime now = now();
        LocalDateTime start = start(period, now);
        LocalDateTime end = next(period, start);
        ChronoUnit unit = period == Period.DAY ? ChronoUnit.HOURS : period == Period.YEAR ? ChronoUnit.MONTHS : ChronoUnit.DAYS;
        String trunc = unit == ChronoUnit.HOURS ? "hour" : unit == ChronoUnit.MONTHS ? "month" : "day";

        Map<LocalDateTime, long[]> counts = new HashMap<>();
        jdbc.query("""
                select date_trunc(?, at) as bucket, kind, count(*) as n from (
                  select delivered_at as at, 'D' as kind from delivery_assignments
                   where delivery_user_id = ? and status = 'DELIVERED' and delivered_at >= ? and delivered_at < ?
                  union all
                  select collected_at, 'P' from return_pickups
                   where delivery_user_id = ? and status = 'COLLECTED' and collected_at >= ? and collected_at < ?
                  union all
                  select failed_at, 'F' from delivery_assignments
                   where delivery_user_id = ? and status = 'FAILED' and failed_at >= ? and failed_at < ?
                  union all
                  select failed_at, 'F' from return_pickups
                   where delivery_user_id = ? and status = 'FAILED' and failed_at >= ? and failed_at < ?
                ) e group by 1, 2""",
                rs -> {
                    long[] c = counts.computeIfAbsent(rs.getObject("bucket", LocalDateTime.class), k -> new long[3]);
                    int i = switch (rs.getString("kind")) { case "D" -> 0; case "P" -> 1; default -> 2; };
                    c[i] += rs.getLong("n");
                },
                trunc, driverId, start, end, driverId, start, end, driverId, start, end, driverId, start, end);

        List<Bucket> buckets = new ArrayList<>();
        for (LocalDateTime b = start; b.isBefore(end); b = b.plus(1, unit)) {
            long[] c = counts.getOrDefault(b, new long[3]);
            buckets.add(new Bucket(b, label(unit, b), c[0], c[1], c[2]));
        }
        return new History(period, start.toLocalDate(), end.toLocalDate().minusDays(1), buckets);
    }

    /** Every active delivery user's figures for the period so far, most completed first. */
    @Transactional(readOnly = true)
    public Leaderboard leaderboard(Period period) {
        LocalDateTime now = now();
        LocalDateTime start = start(period, now);
        List<DriverRow> rows = users.findAll().stream()
                .filter(u -> u.isActive() && u.getRoles().contains(Role.DELIVERY))
                .map(u -> new DriverRow(u.getUserId(), u.getFullName(), figures(u.getUserId(), start, now)))
                .sorted(Comparator.comparingLong((DriverRow r) -> r.figures().completed()).reversed()
                        .thenComparing(DriverRow::driverName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new Leaderboard(period, start.toLocalDate(), now.toLocalDate(), rows);
    }

    // ── Periods ────────────────────────────────────────────────────────────

    LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(com.example.orders.config.TimeConfig.INDIA));
    }

    static LocalDateTime start(Period period, LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        return switch (period) {
            case DAY -> day.atStartOfDay();
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTH -> day.withDayOfMonth(1).atStartOfDay();
            case YEAR -> day.withDayOfYear(1).atStartOfDay();
        };
    }

    static LocalDateTime next(Period period, LocalDateTime start) {
        return switch (period) {
            case DAY -> start.plusDays(1);
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case YEAR -> start.plusYears(1);
        };
    }

    static LocalDateTime previous(Period period, LocalDateTime start) {
        return switch (period) {
            case DAY -> start.minusDays(1);
            case WEEK -> start.minusWeeks(1);
            case MONTH -> start.minusMonths(1);
            case YEAR -> start.minusYears(1);
        };
    }

    private PeriodFigures periodFigures(String driverId, Period period, LocalDateTime now) {
        LocalDateTime start = start(period, now);
        LocalDateTime previousStart = previous(period, start);
        // The same stretch of the period before; never past its end (31 March against 28 February)
        LocalDateTime previousEnd = previousStart.plus(Duration.between(start, now));
        if (previousEnd.isAfter(start)) {
            previousEnd = start;
        }
        return new PeriodFigures(period, start.toLocalDate(), next(period, start).toLocalDate().minusDays(1),
                figures(driverId, start, now.plusNanos(1)), figures(driverId, previousStart, previousEnd));
    }

    // ── Counting ───────────────────────────────────────────────────────────

    Figures figures(String driverId, LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from delivery_assignments where delivery_user_id = ? and status = 'DELIVERED'
                     and delivered_at >= ? and delivered_at < ?) as delivered,
                  (select count(*) from return_pickups where delivery_user_id = ? and status = 'COLLECTED'
                     and collected_at >= ? and collected_at < ?) as collected,
                  (select count(*) from delivery_assignments where delivery_user_id = ? and status = 'FAILED'
                     and failed_at >= ? and failed_at < ?)
                  + (select count(*) from return_pickups where delivery_user_id = ? and status = 'FAILED'
                     and failed_at >= ? and failed_at < ?) as failed,
                  (select count(*) from delivery_assignments where delivery_user_id = ?
                     and status not in ('REASSIGNED', 'CANCELLED') and assigned_at >= ? and assigned_at < ?)
                  + (select count(*) from return_pickups where delivery_user_id = ?
                     and status not in ('REASSIGNED', 'CANCELLED') and assigned_at >= ? and assigned_at < ?) as tasks,
                  (select coalesce(sum(p.amount), 0) from delivery_assignments a
                     join payment_transactions p on p.order_id = a.order_id and p.kind = 'CHARGE' and p.gateway = ?
                    where a.delivery_user_id = ? and a.status = 'DELIVERED'
                      and a.delivered_at >= ? and a.delivered_at < ?) as cash,
                  (select coalesce(sum(pay_amount), 0) from delivery_assignments where delivery_user_id = ?
                     and ((status = 'DELIVERED' and delivered_at >= ? and delivered_at < ?)
                       or (status = 'FAILED' and failed_at >= ? and failed_at < ?)))
                  + (select coalesce(sum(pay_amount), 0) from return_pickups where delivery_user_id = ?
                     and ((status = 'COLLECTED' and collected_at >= ? and collected_at < ?)
                       or (status = 'FAILED' and failed_at >= ? and failed_at < ?))) as earnings,
                  (select avg(r.rating) from delivery_ratings r join delivery_assignments a on a.assignment_id = r.assignment_id
                    where r.delivery_user_id = ? and a.delivered_at >= ? and a.delivered_at < ?) as rating,
                  (select count(*) from delivery_ratings r join delivery_assignments a on a.assignment_id = r.assignment_id
                    where r.delivery_user_id = ? and a.delivered_at >= ? and a.delivered_at < ?) as rating_count""",
                (rs, n) -> {
                    long delivered = rs.getLong("delivered");
                    long collected = rs.getLong("collected");
                    long failed = rs.getLong("failed");
                    long completed = delivered + collected;
                    Double rate = completed + failed == 0 ? null
                            : BigDecimal.valueOf(completed).divide(BigDecimal.valueOf(completed + failed), 4, RoundingMode.HALF_UP).doubleValue();
                    BigDecimal average = rs.getBigDecimal("rating");
                    return new Figures(delivered, collected, completed, failed, rs.getLong("tasks"), rate,
                            rs.getBigDecimal("cash").setScale(2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("earnings").setScale(2, RoundingMode.HALF_UP),
                            average == null ? null : average.setScale(1, RoundingMode.HALF_UP).doubleValue(),
                            rs.getLong("rating_count"));
                },
                driverId, from, to, driverId, from, to, driverId, from, to, driverId, from, to,
                driverId, from, to, driverId, from, to, PaymentGatewayService.CASH, driverId, from, to,
                driverId, from, to, from, to, driverId, from, to, from, to,
                driverId, from, to, driverId, from, to);
    }

    private OpenNow openNow(String driverId) {
        Long deliveries = jdbc.queryForObject("select count(*) from delivery_assignments where delivery_user_id = ? "
                + "and status in ('ASSIGNED', 'PICKED_UP')", Long.class, driverId);
        Long pickups = jdbc.queryForObject("select count(*) from return_pickups where delivery_user_id = ? "
                + "and status in ('ASSIGNED', 'ON_THE_WAY')", Long.class, driverId);
        return new OpenNow(deliveries == null ? 0 : deliveries, pickups == null ? 0 : pickups);
    }

    private String name(String driverId) {
        return users.findById(driverId).map(AppUser::getFullName).orElse(driverId);
    }

    private static String label(ChronoUnit unit, LocalDateTime b) {
        return switch (unit) {
            case HOURS -> String.format("%02d:00", b.getHour());
            case MONTHS -> b.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);
            default -> b.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                    + " " + b.getDayOfMonth();
        };
    }
}
