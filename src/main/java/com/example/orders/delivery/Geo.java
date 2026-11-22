package com.example.orders.delivery;

/** Travel time from a distance. Distances themselves come from PostGIS (ST_Distance), in metres on the globe. */
public final class Geo {

    private Geo() {}

    /**
     * Minutes to cover a straight-line distance by road: roads bend (roadFactor, about 1.4 in Indian cities) and
     * traffic sets the pace (speedKmh). At least one minute, rounded up.
     */
    public static int minutes(double straightKm, double roadFactor, double speedKmh) {
        if (speedKmh <= 0) {
            throw new IllegalArgumentException("speedKmh must be positive");
        }
        return Math.max(1, (int) Math.ceil(straightKm * roadFactor / speedKmh * 60));
    }
}
