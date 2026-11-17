package com.example.orders.delivery;

import com.example.orders.config.TimeConfig;
import com.example.orders.money.IndianState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryEstimatorTest {

    /** A directory with a few PINs, without a database. */
    static PinDirectory directory(Map<String, IndianState> places) {
        return new PinDirectory(null, "") {
            @Override
            public Optional<Place> find(String pin) {
                return Optional.ofNullable(places.get(pin)).map(s -> new Place(pin, "Somewhere", s));
            }

            @Override
            public long size() {
                return places.size();
            }
        };
    }

    private static final DeliveryEstimator ESTIMATOR = new DeliveryEstimator(
            directory(Map.of("560038", IndianState.KARNATAKA, "795001", IndianState.MANIPUR)),
            HolidayCalendar.of(List.of()), Clock.systemUTC());

    // Monday 10 Nov 2026
    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, TimeConfig.INDIA);
    }

    @Test
    void orderedBeforeTwoOnAWorkingDay_itLeavesToday_andArrivesInTwoToThreeDays() {
        DeliveryEstimator.Estimate e = ESTIMATOR.estimate("560038", true, at(10, 11, 30));
        assertThat(e.outcome()).isEqualTo(DeliveryEstimator.Outcome.DELIVERABLE);
        assertThat(e.earliest()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(e.latest()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(e.dispatchCountdown()).isEqualTo(Duration.ofMinutes(150));
        assertThat(e.approximate()).isFalse();
        assertThat(e.state()).isEqualTo("Karnataka");
    }

    @Test
    void afterTheCutOff_orOnASunday_itLeavesTheNextWorkingDay_andSundaysAreNotCounted() {
        // Monday 3 PM: leaves Tuesday, two working days later is Thursday
        assertThat(ESTIMATOR.estimate("560038", true, at(10, 15, 0)).earliest()).isEqualTo(LocalDate.of(2026, 11, 13));
        // Saturday 3 PM: leaves Monday 16th (Sunday skipped), arrives Wednesday 18th
        DeliveryEstimator.Estimate saturday = ESTIMATOR.estimate("560038", true, at(14, 15, 0));
        assertThat(saturday.earliest()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(saturday.dispatchCountdown()).isNull();
    }

    @Test
    void theNorthEastTakesLonger_andAnUnknownPinIsRefused() {
        assertThat(ESTIMATOR.estimate("795001", true, at(10, 11, 0)).earliest()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(ESTIMATOR.estimate("110001", true, at(10, 11, 0)).outcome()).isEqualTo(DeliveryEstimator.Outcome.UNKNOWN_PIN);
        assertThat(ESTIMATOR.estimate("012345", true, at(10, 11, 0)).outcome()).isEqualTo(DeliveryEstimator.Outcome.INVALID_PIN);
        assertThat(ESTIMATOR.estimate("560038", false, at(10, 11, 0)).outcome()).isEqualTo(DeliveryEstimator.Outcome.OUT_OF_STOCK);
    }

    @Test
    void beforeTheDirectoryIsImported_theEstimateIsApproximate_andWider() {
        DeliveryEstimator noDirectory = new DeliveryEstimator(directory(Map.of()), HolidayCalendar.of(List.of()), Clock.systemUTC());
        DeliveryEstimator.Estimate e = noDirectory.estimate("110001", true, at(10, 11, 0));
        assertThat(e.outcome()).isEqualTo(DeliveryEstimator.Outcome.DELIVERABLE);
        assertThat(e.approximate()).isTrue();
        assertThat(e.earliest()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(e.latest()).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test
    void holidaysAreSkipped_nationalOnesEverywhere_aStatesOnlyThere() {
        // Tuesday 11 Nov is a national holiday; Wednesday 12 Nov one in Karnataka only
        HolidayCalendar calendar = HolidayCalendar.of(List.of(
                new HolidayCalendar.Holiday(1, LocalDate.of(2026, 11, 11), "Festival", null, null),
                new HolidayCalendar.Holiday(2, LocalDate.of(2026, 11, 12), "Rajyotsava (late)", "29", "Karnataka")));
        DeliveryEstimator estimator = new DeliveryEstimator(
                directory(Map.of("560038", IndianState.KARNATAKA, "400001", IndianState.MAHARASHTRA)), calendar, Clock.systemUTC());

        // Monday 11:30 in Karnataka: leaves Monday; Tue (national) and Wed (state) do not count → Thu, Sat; then Mon
        DeliveryEstimator.Estimate karnataka = estimator.estimate("560038", true, at(10, 11, 30));
        assertThat(karnataka.earliest()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(karnataka.latest()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(karnataka.holidays()).extracting(HolidayCalendar.Holiday::name)
                .containsExactly("Festival", "Rajyotsava (late)");

        // Maharashtra works on the 12th: only the national holiday is skipped
        DeliveryEstimator.Estimate mumbai = estimator.estimate("400001", true, at(10, 11, 30));
        assertThat(mumbai.earliest()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(mumbai.holidays()).extracting(HolidayCalendar.Holiday::name).containsExactly("Festival");

        // Ordered on the national holiday: nothing leaves the hub until Wednesday, so Thu and Sat
        assertThat(estimator.estimate("400001", true, at(11, 9, 0)).dispatchCountdown()).isNull();
        assertThat(estimator.estimate("400001", true, at(11, 9, 0)).earliest()).isEqualTo(LocalDate.of(2026, 11, 14));

        // Days a customer may book skip Sundays and holidays
        assertThat(estimator.deliveryDaysAfter(LocalDate.of(2026, 11, 10), 3, IndianState.KARNATAKA))
                .containsExactly(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16));
    }
}
