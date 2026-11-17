package com.example.orders.delivery;

import com.example.orders.config.TimeConfig;
import com.example.orders.money.IndianState;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "Delivered by Thu, 13 Nov" for a PIN code. Orders go out from the regional hub that serves the
 * delivery state (north, south, east, west), so the estimate is the same for every product in stock:
 *
 * - Ordered by 2 PM on a dispatch day, it leaves that day; otherwise the next dispatch day.
 *   Hubs do not dispatch on Sundays or national holidays.
 * - 2 to 3 delivery days in transit; 3 more for hill states, the North East and the islands. Sundays,
 *   national holidays and the delivery state's own holidays are not delivery days (HolidayCalendar).
 * - A PIN that is not in the India Post directory is refused (UNKNOWN_PIN). Only while the directory
 *   has not been imported yet, any well-formed PIN gets a wider estimate allowing for the farthest
 *   state, marked approximate.
 */
@Service
public class DeliveryEstimator {

    static final LocalTime CUT_OFF = LocalTime.of(14, 0);
    static final int TRANSIT_DAYS = 2;
    static final int SLACK_DAYS = 1;
    static final int REMOTE_EXTRA_DAYS = 3;

    /** Hill states, the North East and the islands: longer by road and air. */
    static final Set<IndianState> REMOTE = Set.of(IndianState.JAMMU_AND_KASHMIR, IndianState.LADAKH,
            IndianState.HIMACHAL_PRADESH, IndianState.ARUNACHAL_PRADESH, IndianState.NAGALAND, IndianState.MANIPUR,
            IndianState.MIZORAM, IndianState.TRIPURA, IndianState.MEGHALAYA, IndianState.SIKKIM,
            IndianState.ANDAMAN_AND_NICOBAR, IndianState.LAKSHADWEEP);

    public enum Outcome { DELIVERABLE, UNKNOWN_PIN, INVALID_PIN, OUT_OF_STOCK }

    /**
     * @param dispatchCountdown time left to order for dispatch today, or null
     * @param approximate       true before the PIN directory is imported, when the state is not known
     * @param holidays          holidays between today and the latest day that the estimate skipped
     * @param deliveryState     the state the PIN is in, or null when not known
     */
    public record Estimate(Outcome outcome, String pin, String district, String state, LocalDate earliest,
                           LocalDate latest, boolean approximate, Duration dispatchCountdown,
                           List<HolidayCalendar.Holiday> holidays, IndianState deliveryState) {}

    private final PinDirectory pins;
    private final HolidayCalendar calendar;
    private final Clock clock;

    public DeliveryEstimator(PinDirectory pins, HolidayCalendar calendar, Clock clock) {
        this.pins = pins;
        this.calendar = calendar;
        this.clock = clock;
    }

    public Estimate estimate(String pin, boolean inStock) {
        return estimate(pin, inStock, now());
    }

    /** Now in India, where the cut-off and the days are counted. */
    public ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(TimeConfig.INDIA);
    }

    Estimate estimate(String rawPin, boolean inStock, ZonedDateTime now) {
        String pin = rawPin == null ? "" : rawPin.replaceAll("\\s", "");
        if (!pin.matches("[1-9][0-9]{5}")) {
            return refused(Outcome.INVALID_PIN, pin);
        }
        var place = pins.find(pin);
        boolean directoryLoaded = place.isPresent() || pins.size() > 0;
        if (place.isEmpty() && directoryLoaded) {
            return refused(Outcome.UNKNOWN_PIN, pin);
        }
        IndianState state = place.map(PinDirectory.Place::state).orElse(null);
        String district = place.map(PinDirectory.Place::district).orElse(null);
        String stateName = state == null ? null : state.displayName();
        if (!inStock) {
            return new Estimate(Outcome.OUT_OF_STOCK, pin, district, stateName, null, null, place.isEmpty(), null,
                    List.of(), state);
        }

        LocalDate dispatch = dispatchDay(now);
        int transit = TRANSIT_DAYS;
        int slack = SLACK_DAYS;
        if (state != null) {
            if (REMOTE.contains(state)) {
                transit += REMOTE_EXTRA_DAYS;
            }
        } else {
            // State not known: allow for the farthest
            transit += 1;
            slack += REMOTE_EXTRA_DAYS;
        }
        LocalDate earliest = addDeliveryDays(dispatch, transit, state);
        LocalDate latest = addDeliveryDays(earliest, slack, state);
        Duration countdown = dispatch.equals(now.toLocalDate())
                ? Duration.between(now.toLocalTime(), CUT_OFF) : null;
        List<HolidayCalendar.Holiday> skipped = new ArrayList<>();
        for (LocalDate day = now.toLocalDate(); !day.isAfter(latest); day = day.plusDays(1)) {
            calendar.holidayOn(day, state).ifPresent(skipped::add);
        }
        return new Estimate(Outcome.DELIVERABLE, pin, district, stateName, earliest, latest, place.isEmpty(), countdown,
                skipped, state);
    }

    /** The next delivery days in the state from (not including) a day: the days a customer may book. */
    public List<LocalDate> deliveryDaysAfter(LocalDate from, int count, IndianState state) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = from;
        while (days.size() < count) {
            day = day.plusDays(1);
            if (calendar.isDeliveryDay(day, state)) {
                days.add(day);
            }
        }
        return days;
    }

    public boolean isDeliveryDay(LocalDate day, IndianState state) {
        return calendar.isDeliveryDay(day, state);
    }

    LocalDate dispatchDay(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        if (calendar.isDispatchDay(today) && now.toLocalTime().isBefore(CUT_OFF)) {
            return today;
        }
        LocalDate day = today.plusDays(1);
        while (!calendar.isDispatchDay(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    LocalDate addDeliveryDays(LocalDate from, int days, IndianState state) {
        LocalDate day = from;
        int added = 0;
        while (added < days) {
            day = day.plusDays(1);
            if (calendar.isDeliveryDay(day, state)) {
                added++;
            }
        }
        return day;
    }

    private static Estimate refused(Outcome outcome, String pin) {
        return new Estimate(outcome, pin, null, null, null, null, false, null, List.of(), null);
    }
}
