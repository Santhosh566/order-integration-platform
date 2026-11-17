package com.example.orders.delivery;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.money.IndianState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Which days parcels move. Sundays never do; a national holiday stops dispatch and delivery
 * everywhere; a state's holiday stops delivery in that state only.
 *
 * Every estimate asks, so the holidays are held in memory and read again from the database every
 * few minutes (other pods' changes arrive within that time) and at once after a change on this pod.
 */
@Service
public class HolidayCalendar {

    static final Duration REFRESH = Duration.ofMinutes(5);

    /** @param stateCode GST state code; null for the whole country */
    public record Holiday(long id, LocalDate day, String name, String stateCode, String stateName) {
        public boolean national() {
            return stateCode == null;
        }
    }

    public record NewHoliday(LocalDate day, String name, String stateCode) {}

    private record Snapshot(List<Holiday> holidays, Instant loadedAt) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Snapshot snapshot;

    @Autowired
    public HolidayCalendar(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A calendar with these holidays only, for tests and tools without a database. */
    public static HolidayCalendar of(List<Holiday> holidays) {
        return new HolidayCalendar(null, Clock.systemUTC()) {
            @Override
            List<Holiday> load() {
                return holidays;
            }
        };
    }

    // ── Questions the estimates ask ────────────────────────────────────────

    /** The hubs dispatch: not on Sundays or national holidays. */
    public boolean isDispatchDay(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SUNDAY && holidayOn(day, null).isEmpty();
    }

    /** Parcels are delivered in the state (null: anywhere, so national holidays only). */
    public boolean isDeliveryDay(LocalDate day, IndianState state) {
        return day.getDayOfWeek() != DayOfWeek.SUNDAY && holidayOn(day, state).isEmpty();
    }

    /** The national holiday on that day, or the state's when a state is given. */
    public Optional<Holiday> holidayOn(LocalDate day, IndianState state) {
        String code = state == null ? null : state.gstCode();
        return current().stream()
                .filter(h -> h.day().equals(day) && (h.national() || h.stateCode().equals(code)))
                .findFirst();
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Holiday> year(int year) {
        return jdbc.query(SELECT + "where extract(year from day) = ? order by day, state_code nulls first", ROW, year);
    }

    @Transactional
    public Holiday add(NewHoliday request, String admin) {
        if (request == null || request.day() == null) {
            throw new InvalidOrderException("Choose the day");
        }
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw new InvalidOrderException("The name must be 1 to 100 characters");
        }
        String code = null;
        if (request.stateCode() != null && !request.stateCode().isBlank()) {
            code = IndianState.find(request.stateCode())
                    .orElseThrow(() -> new InvalidOrderException(request.stateCode() + " is not an Indian state or union territory"))
                    .gstCode();
        }
        if (request.day().getDayOfWeek() == DayOfWeek.SUNDAY) {
            throw new InvalidOrderException("Sundays are never delivery days; there is no need to add one");
        }
        try {
            Long id = jdbc.queryForObject("insert into public_holidays (day, name, state_code, created_by) values (?, ?, ?, ?) "
                    + "returning holiday_id", Long.class, request.day(), name, code, admin);
            changed();
            return jdbc.queryForObject(SELECT + "where holiday_id = ?", ROW, id);
        } catch (DuplicateKeyException e) {
            throw new InvalidOrderException("There is already a holiday on " + request.day()
                    + (code == null ? " for the whole country" : " in that state"));
        }
    }

    @Transactional
    public void remove(long id) {
        if (jdbc.update("delete from public_holidays where holiday_id = ?", id) == 0) {
            throw new NotFoundException("No holiday " + id);
        }
        changed();
    }

    // ── Cache ──────────────────────────────────────────────────────────────

    private List<Holiday> current() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s == null || s.loadedAt().plus(REFRESH).isBefore(now)) {
            s = new Snapshot(List.copyOf(load()), now);
            snapshot = s;
        }
        return s.holidays();
    }

    /** From a month ago: nothing older can affect an estimate. */
    List<Holiday> load() {
        return jdbc.query(SELECT + "where day >= current_date - 31 order by day", ROW);
    }

    /** Forgets the holidays once the change is committed, so the next question reads the new ones. */
    void changed() {
        snapshot = null;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            snapshot = null;
                        }
                    });
        }
    }

    private static final String SELECT = "select holiday_id, day, name, state_code from public_holidays ";

    private static final org.springframework.jdbc.core.RowMapper<Holiday> ROW = (rs, n) -> {
        String code = rs.getString("state_code");
        return new Holiday(rs.getLong("holiday_id"), rs.getObject("day", LocalDate.class), rs.getString("name"), code,
                code == null ? null : IndianState.find(code).map(IndianState::displayName).orElse(code));
    };
}
