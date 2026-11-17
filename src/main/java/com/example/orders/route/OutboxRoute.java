package com.example.orders.route;

import com.example.orders.service.OutboxService;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.springframework.stereotype.Component;

/**
 * Step 4 & Phase 1.3 — Transactional Outbox dispatcher with Multi-Pod Concurrency Safety.
 *
 * EIP patterns used:
 *   Timer                 → wakes up every 5 seconds
 *   Message Filter        → skips the run when the outbox is empty
 *   Splitter              → processes each PENDING row independently
 *   Bean                  → claims rows with FOR UPDATE SKIP LOCKED, marks PUBLISHED after Kafka confirms
 *
 * Multi-Pod Safety:
 *   - Uses PostgreSQL `FOR UPDATE SKIP LOCKED` to lock rows atomically.
 *   - Immediately claims selected rows by transitioning status from PENDING to PROCESSING.
 *   - Multiple Kubernetes replicas running concurrently will skip locked rows without race conditions.
 *   - Self-heals: any event stuck in PROCESSING > 60s (e.g. from an ungraceful pod termination) is reset to PENDING.
 */
@Component
public class OutboxRoute extends RouteBuilder {

    private final OutboxService outboxService;

    public OutboxRoute(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Override
    public void configure() {

        // If Kafka is down, revert the row to PENDING so it will be retried on next poll.
        onException(Exception.class)
            .handled(true)
            .log(LoggingLevel.ERROR,
                 "[OUTBOX] Publish failed for event ${header.outboxEventId}: ${exception.message}. Reverting to PENDING.")
            .bean(outboxService, "markPending(${header.outboxEventId})");

        from("timer:outboxPoller?period=5000&delay=5000")
            .routeId("outbox-poller")
            .log(LoggingLevel.DEBUG, "[OUTBOX] Polling outbox_events for PENDING rows...")

            // Auto-recovery: reset any orphaned PROCESSING rows older than 60 seconds
            .bean(outboxService, "recoverStaleProcessingEvents")

            // Bean: select and claim up to 20 PENDING rows using SELECT ... FOR UPDATE SKIP LOCKED
            .bean(outboxService, "claimPendingEvents")

            // EIP: Message Filter — nothing to send this tick, skip
            .filter(simple("${body.size} > 0"))
                .log("[OUTBOX] Claimed ${body.size} event(s) for Kafka publication.")

                // EIP: Splitter — body is List<OutboxEvent>, split into individual exchanges
                .split(body())
                    // Stash the OutboxEvent ID and Kafka message key
                    .setHeader("outboxEventId", simple("${body.id}"))
                    .setHeader(KafkaConstants.KEY, simple("${body.orderId}"))

                    // Replace body with the raw JSON payload that Kafka will receive
                    .setBody(simple("${body.payload}"))
                    .log("[OUTBOX] Publishing event ${header.outboxEventId} (Order: ${header.CamelKafkaKey}) to kafka:order.created")

                    // Camel Kafka producer — sends the JSON string to the topic
                    .to("kafka:order.created?brokers={{kafka.brokers}}")

                    // Bean: mark the row PUBLISHED now that Kafka confirmed receipt
                    .bean(outboxService, "markPublished(${header.outboxEventId})")
                .end()

            .end();
    }
}
