package com.example.orders.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeoTest {

    @Test
    void minutesAddRoadBendsAndRoundUp_neverBelowOne() {
        // 5 km straight → 7 km by road → 21 minutes at 20 km/h
        assertThat(Geo.minutes(5, 1.4, 20)).isEqualTo(21);
        assertThat(Geo.minutes(0, 1.4, 20)).isEqualTo(1);
        assertThat(Geo.minutes(0.01, 1.4, 20)).isEqualTo(1);
    }

    @Test
    void aPinTakesTheMedianOfItsPostOffices_andIgnoresPositionsOutsideIndia() {
        assertThat(PinDirectory.median(new double[] {12.0, 12.2, 30.0})).isEqualTo(12.2);
        assertThat(PinDirectory.median(new double[] {12.0, 13.0})).isEqualTo(12.5);
        assertThat(PinDirectory.position(List.of("12.97", "77.64"), 0, 1)).containsExactly(12.97, 77.64);
        assertThat(PinDirectory.position(List.of("NA", "NA"), 0, 1)).isNull();
        // Swapped columns put it in the sea
        assertThat(PinDirectory.position(List.of("77.64", "12.97"), 0, 1)).isNull();
        assertThat(PinDirectory.position(List.of("0", "0"), 0, 1)).isNull();
    }
}
