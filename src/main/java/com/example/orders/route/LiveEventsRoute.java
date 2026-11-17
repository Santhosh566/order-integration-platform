package com.example.orders.route;

import com.example.orders.service.LivePodId;
import com.example.orders.service.OrderEventBroadcaster;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * EIP: Publish-Subscribe Channel across pods. Every pod reads every live update from
 * platform.events and passes it to its own connected browsers (OrderEventBroadcaster).
 *
 * Each pod uses a consumer group of its own, so Kafka gives it all partitions instead of sharing
 * them out as it does for order.created. It starts at the newest event and stores no offsets: a
 * browser that was disconnected reloads what it missed when it reconnects, so old events are never
 * needed and the group leaves nothing behind when the pod stops.
 */
@Component
public class LiveEventsRoute extends RouteBuilder {

    private final LivePodId podId;

    public LiveEventsRoute(LivePodId podId) {
        this.podId = podId;
    }

    @Override
    public void configure() {
        // A bad event is logged and skipped; it must never stop the stream
        onException(Exception.class)
            .handled(true)
            .log(LoggingLevel.WARN, "[SSE] Skipped a live update that could not be read: ${exception.message}");

        from("kafka:{{orders.kafka.events-topic}}?brokers={{kafka.brokers}}"
                + "&groupId=live-updates-" + podId.value()
                + "&autoOffsetReset=latest&autoCommitEnable=false")
            .routeId("live-events-consumer")
            .convertBodyTo(String.class)
            .bean(OrderEventBroadcaster.class, "deliverJson");
    }
}
