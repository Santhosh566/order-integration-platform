package com.example.orders.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class CartReminderQuietHoursTest {

    private static final LocalTime NINE_PM = LocalTime.of(21, 0);
    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    @Test
    void quietHoursRunPastMidnight() {
        assertThat(CartReminderService.quiet(LocalTime.of(20, 59), NINE_PM, NINE_AM)).isFalse();
        assertThat(CartReminderService.quiet(LocalTime.of(21, 0), NINE_PM, NINE_AM)).isTrue();
        assertThat(CartReminderService.quiet(LocalTime.of(2, 30), NINE_PM, NINE_AM)).isTrue();
        assertThat(CartReminderService.quiet(LocalTime.of(8, 59), NINE_PM, NINE_AM)).isTrue();
        assertThat(CartReminderService.quiet(LocalTime.of(9, 0), NINE_PM, NINE_AM)).isFalse();
        assertThat(CartReminderService.quiet(LocalTime.NOON, NINE_PM, NINE_AM)).isFalse();
    }

    @Test
    void quietHoursWithinOneDay_andTheSameTimeTwiceTurnsThemOff() {
        assertThat(CartReminderService.quiet(LocalTime.of(13, 30), LocalTime.of(13, 0), LocalTime.of(14, 0))).isTrue();
        assertThat(CartReminderService.quiet(LocalTime.of(14, 0), LocalTime.of(13, 0), LocalTime.of(14, 0))).isFalse();
        assertThat(CartReminderService.quiet(LocalTime.of(3, 0), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)).isFalse();
    }
}
