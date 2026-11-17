package com.example.orders.delivery;

import com.example.orders.delivery.DriverStatsService.Period;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Calendar periods in India time; weeks start on Monday. */
class DriverStatsPeriodsTest {

    @Test
    void sundayNightIsStillThisWeek_mondayStartsTheNext() {
        LocalDateTime sundayNight = LocalDateTime.of(2026, 11, 15, 23, 59);
        LocalDateTime mondayMorning = LocalDateTime.of(2026, 11, 16, 0, 0);

        assertThat(DriverStatsService.start(Period.WEEK, sundayNight)).isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
        assertThat(DriverStatsService.start(Period.WEEK, mondayMorning)).isEqualTo(mondayMorning);
        assertThat(DriverStatsService.next(Period.WEEK, LocalDateTime.of(2026, 11, 9, 0, 0))).isEqualTo(mondayMorning);
    }

    @Test
    void monthsAndYears_startOnTheFirst() {
        LocalDateTime newYearsEve = LocalDateTime.of(2026, 12, 31, 23, 30);

        assertThat(DriverStatsService.start(Period.DAY, newYearsEve)).isEqualTo(LocalDateTime.of(2026, 12, 31, 0, 0));
        assertThat(DriverStatsService.start(Period.MONTH, newYearsEve)).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
        assertThat(DriverStatsService.start(Period.YEAR, newYearsEve)).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(DriverStatsService.next(Period.YEAR, LocalDateTime.of(2026, 1, 1, 0, 0))).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        assertThat(DriverStatsService.previous(Period.MONTH, LocalDateTime.of(2026, 3, 1, 0, 0))).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
    }
}
