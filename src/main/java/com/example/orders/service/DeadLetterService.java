package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.OutboxEvent;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.OutboxEventRepository;
import com.example.orders.security.CurrentUser;
import java.util.EnumSet;
import java.util.Set;

import com.example.orders.exception.InvalidOrderStateException;
import org.apache.camel.component.kafka.KafkaConstants;
import com.example.orders.model.DeadLetterEvent;
import com.example.orders.repository.DeadLetterEventRepository;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service managing dead-lettered messages.
 * Quarantines failures, records diagnostic metadata, and supports operator replays.
 */
@Service("deadLetterService")
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]+)\"");

    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ProducerTemplate producerTemplate;
    private final OrderEventBroadcaster eventBroadcaster;
    private final InventoryService inventoryService;
    private final IdempotentRepository kafkaOrderIdRepository;
    private final OrderRepository orderRepository;
    private final CurrentUser currentUser;
    private final OutboxEventRepository outboxEvents;

    /** Orders a replay can still take through the pipeline (see OrderStatus: these can go back to PENDING, or are there). */
    private static final Set<OrderStatus> REPLAYABLE =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.PAYMENT_FAILED, OrderStatus.FULFILLMENT_FAILED);

    @Value("${kafka.brokers:localhost:9092}")
    private String kafkaBrokers;

    public DeadLetterService(DeadLetterEventRepository deadLetterEventRepository,
                             ProducerTemplate producerTemplate,
                             OrderEventBroadcaster eventBroadcaster,
                             InventoryService inventoryService,
                             @Qualifier("kafkaOrderIdRepository") IdempotentRepository kafkaOrderIdRepository,
                             OrderRepository orderRepository,
                             CurrentUser currentUser,
                             OutboxEventRepository outboxEvents) {
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.producerTemplate = producerTemplate;
        this.eventBroadcaster = eventBroadcaster;
        this.inventoryService = inventoryService;
        this.kafkaOrderIdRepository = kafkaOrderIdRepository;
        this.orderRepository = orderRepository;
        this.currentUser = currentUser;
        this.outboxEvents = outboxEvents;
    }

    /**
     * Invoked by Dead Letter Channel EIP when message processing exhausts all retry attempts.
     */
    @Transactional
    public DeadLetterEvent recordFailure(Exchange exchange) {
        String body = exchange.getMessage().getBody(String.class);
        if (body == null && exchange.getIn().getBody() != null) {
            body = exchange.getIn().getBody(String.class);
        }

        Exception caughtException = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        Integer redeliveries = exchange.getMessage().getHeader(Exchange.REDELIVERY_COUNTER, Integer.class);
        if (redeliveries == null) {
            redeliveries = exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER, Integer.class);
        }

        String orderId = exchange.getProperty("currentOrderId", String.class);
        if (orderId == null) {
            orderId = exchange.getMessage().getHeader(KafkaConstants.KEY, String.class);
        }
        if (orderId == null && body != null) {
            Matcher matcher = ORDER_ID_PATTERN.matcher(body);
            if (matcher.find()) {
                orderId = matcher.group(1);
            }
        }

        String errorMsg = caughtException != null ? caughtException.getMessage() : "Unknown processing error";
        String exClass = caughtException != null ? caughtException.getClass().getName() : "UnknownException";

        DeadLetterEvent event = new DeadLetterEvent(
                orderId,
                "order.created",
                body != null ? body : "<empty payload>",
                errorMsg,
                exClass,
                redeliveries != null ? redeliveries : 0
        );

        DeadLetterEvent saved = deadLetterEventRepository.save(event);
        log.warn("[DLQ] Quarantined message for orderId='{}' (DLQ Event ID: {}) into dead_letter_events table",
                orderId, saved.getId());
        eventBroadcaster.broadcast("DLQ_UPDATED", saved);
        return saved;
    }

    /**
     * Records an order that recovery gave up on, so an operator can replay it.
     */
    @Transactional
    public DeadLetterEvent recordStuckOrder(String orderId, String payload, String reason) {
        DeadLetterEvent saved = deadLetterEventRepository.save(
                new DeadLetterEvent(orderId, "order.created", payload, reason, "StuckOrder", 0));
        eventBroadcaster.broadcast("DLQ_UPDATED", saved);
        return saved;
    }

    /**
     * Closes an entry without replaying it, with a note saying why (for the next operator and the audit).
     */
    @Transactional
    public DeadLetterEvent dismiss(Long eventId, String note) {
        DeadLetterEvent event = deadLetterEventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("DLQ event " + eventId + " not found"));
        if (!event.isOpen()) {
            throw new InvalidOrderStateException("DLQ event " + eventId + " is already "
                    + (event.isReplayed() ? "replayed" : "dismissed"));
        }
        if (note == null || note.isBlank() || note.length() > 500) {
            throw new InvalidOrderException("A note of 1 to 500 characters is required to dismiss an entry");
        }
        event.dismiss(currentUser.require().userId(), note.trim());
        log.info("[DLQ] Event {} (order {}) dismissed by {}: {}", eventId, event.getOrderId(), event.getDismissedBy(), note);
        DeadLetterEvent saved = deadLetterEventRepository.save(event);
        eventBroadcaster.broadcast("DLQ_UPDATED", saved);
        return saved;
    }

    /**
     * Lists all quarantined messages.
     */
    public List<DeadLetterEvent> findAll() {
        return deadLetterEventRepository.findAllByOrderByFailedAtDesc();
    }

    /**
     * Finds a single dead letter event by id.
     */
    public DeadLetterEvent findById(Long id) {
        return deadLetterEventRepository.findById(id).orElse(null);
    }

    /**
     * Replays a quarantined dead letter event back to Kafka topic order.created.
     * Only an order that can still be processed is sent again; a cancelled or finished one
     * would not be, and the entry would wrongly read "replayed". Such entries are dismissed.
     */
    @Transactional
    public DeadLetterEvent replay(Long eventId) {
        DeadLetterEvent event = deadLetterEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return null;
        }
        if (event.isReplayed()) {
            throw new InvalidOrderStateException("DLQ event " + eventId + " was already replayed");
        }
        if (event.getDismissedAt() != null) {
            throw new InvalidOrderStateException("DLQ event " + eventId + " was dismissed by " + event.getDismissedBy());
        }
        if (event.getOrderId() != null) {
            orderRepository.findById(event.getOrderId()).ifPresent(order -> {
                if (!REPLAYABLE.contains(order.getStatus())) {
                    throw new InvalidOrderStateException("Order " + order.getOrderId() + " is " + order.getStatus()
                            + " and cannot be processed again. Dismiss this entry instead.");
                }
                if (order.getCancelledTotal().signum() > 0) {
                    // Running it again would charge and reserve the whole order for fewer goods
                    throw new InvalidOrderStateException("Order " + order.getOrderId()
                            + " had items cancelled before dispatch and cannot be processed again. Dismiss this entry instead.");
                }
            });
        }

        // The order must look new to the pipeline, or the replay would be dropped as a duplicate
        if (event.getOrderId() != null) {
            inventoryService.reopenForReplay(event.getOrderId());
            kafkaOrderIdRepository.remove(event.getOrderId());
        }

        if (event.getOrderId() != null) {
            // Through the outbox: published once this transaction has committed the order as PENDING
            outboxEvents.save(OutboxEvent.replayOf(event.getOrderId(), event.getPayload()));
            log.info("[DLQ REPLAY] Event ID {} (orderId: {}) queued in the outbox for order.created",
                    event.getId(), event.getOrderId());
        } else {
            // No order behind it, so no database change to wait for
            String targetEndpoint = "kafka:order.created?brokers=" + kafkaBrokers;
            log.info("[DLQ REPLAY] Re-publishing event ID {} (no order id) to {}", event.getId(), targetEndpoint);
            producerTemplate.sendBody(targetEndpoint, event.getPayload());
        }

        event.replayedBy(currentUser.require().userId());
        DeadLetterEvent saved = deadLetterEventRepository.save(event);
        eventBroadcaster.broadcast("DLQ_REPLAYED", saved);
        return saved;
    }
}
