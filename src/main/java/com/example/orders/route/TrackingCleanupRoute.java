package com.example.orders.route;

import com.example.orders.delivery.LiveTrackingService;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/** Polling Consumer EIP: deletes drivers' location trails once they are older than a week. */
@Component
public class TrackingCleanupRoute extends RouteBuilder {

    private final LiveTrackingService tracking;

    public TrackingCleanupRoute(LiveTrackingService tracking) {
        this.tracking = tracking;
    }

    @Override
    public void configure() {
        from("timer:trackingCleanup?period={{orders.live-tracking.cleanup-poll-ms}}&delay=60000")
            .routeId("tracking-cleanup")
            .bean(tracking, "cleanUp");
    }
}
