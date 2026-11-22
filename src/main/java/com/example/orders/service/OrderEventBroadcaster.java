package com.example.orders.service;

import com.example.orders.model.LiveEvent;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatusChanged;
import com.example.orders.security.Viewer;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.kafka.KafkaConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live updates for the web pages (Server-Sent Events), across every pod.
 *
 * A browser keeps its stream open to one pod, but the change it wants to hear about may happen on
 * another (Kafka gives each pod different orders; the load balancer spreads requests). So no pod
 * writes to browsers directly: each change is published to the platform.events topic, and every pod
 * reads every event (each with its own consumer group, see LiveEventsRoute) and passes it to the
 * browsers connected to it, filtered per viewer: admins everything, customers their own orders.
 */
@Service
public class OrderEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(OrderEventBroadcaster.class);

    // Dates as ISO text, like the rest of the API
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final Map<SseEmitter, Viewer> emitters = new ConcurrentHashMap<>();
    private final ProducerTemplate producer;
    private final String eventsEndpoint;
    private final String podId;

    public OrderEventBroadcaster(ProducerTemplate producer,
                                 @Value("${orders.kafka.events-topic}") String eventsTopic,
                                 @Value("${kafka.brokers}") String brokers,
                                 LivePodId podId) {
        this.producer = producer;
        this.podId = podId.value();
        // Never hold up the caller for long: an order change is already committed when this runs,
        // and a live update that cannot be sent is not worth blocking the pipeline for
        this.eventsEndpoint = "kafka:" + eventsTopic + "?brokers=" + brokers
                + "&maxBlockMs=2000&requestTimeoutMs=3000&deliveryTimeoutMs=5000&lingerMs=0";
    }

    /**
     * Subscribes a browser to the live stream of this pod.
     */
    public SseEmitter subscribe(Viewer viewer) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minute timeout; the page reconnects

        emitters.put(emitter, viewer);
        log.info("[SSE] Browser connected for {} on {}. Connected here: {}", viewer.userId(), podId, emitters.size());

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        emitter.onError(e -> {
            emitter.complete();
            emitters.remove(emitter);
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data("{\"status\":\"CONNECTED\",\"message\":\"Connected to Order Platform SSE Event Stream\"}"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    /**
     * Shares a change with every pod, this one included. If Kafka cannot take it, at least the
     * browsers on this pod hear about it.
     */
    public void broadcast(String eventName, Object data) {
        LiveEvent event = toEvent(eventName, data);
        // Inside a transaction, wait for the commit: a browser that reloads on this event must find
        // the change saved, and a rolled-back change must not be announced at all
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    /**
     * Shares an event about one order that is not the order itself (a driver's position): admins get
     * staffData, and the order's customer gets customerData. Waits for the commit, like broadcast.
     */
    public void broadcastForOrder(String eventName, String orderId, String customerId, Object staffData, Object customerData) {
        LiveEvent event = new LiveEvent(eventName, orderId, customerId, json(staffData),
                customerData == null ? null : json(customerData), podId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
        } else {
            publish(event);
        }
    }

    private void publish(LiveEvent event) {
        String eventName = event.name();
        try {
            producer.sendBodyAndHeader(eventsEndpoint, MAPPER.writeValueAsString(event), KafkaConstants.KEY, event.key());
        } catch (Exception e) {
            log.warn("[SSE] Could not share {} on Kafka ({}); telling this pod's browsers only", eventName, e.getMessage());
            deliver(event);
        }
    }

    /** Every pod runs this for every event on platform.events (LiveEventsRoute). */
    public void deliverJson(String json) {
        deliver(MAPPER.readValue(json, LiveEvent.class));
    }

    /** Passes an event to the browsers connected to this pod that may see it. */
    public void deliver(LiveEvent event) {
        emitters.forEach((emitter, viewer) -> {
            String payload = event.payloadFor(viewer);
            if (payload == null) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event.name()).data(payload));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        });
    }

    LiveEvent toEvent(String name, Object data) {
        if (data instanceof Order order) {
            return new LiveEvent(name, order.getOrderId(), order.getCustomerId(), json(order),
                    json(OrderViews.customerView(order)), podId);
        }
        // Dead letters and routes carry other customers' data: staff only
        return new LiveEvent(name, name, null, json(data), null, podId);
    }

    private static String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * Admins see every event. Anyone else sees only changes to their own orders, never the DLQ or
     * route events, which carry other customers' data. (LiveEvent.payloadFor applies the same rule.)
     */
    static boolean visibleTo(Viewer viewer, Object data) {
        if (viewer.isAdmin()) {
            return true;
        }
        return data instanceof Order order && viewer.userId().equals(order.getCustomerId());
    }

    /**
     * Shares every order change, only after it has been committed.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onOrderStatusChanged(OrderStatusChanged event) {
        broadcast("ORDER_UPDATED", event.order());
    }

    /**
     * Heartbeat every 25 seconds keeps idle connections (and proxies between) from closing them.
     */
    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters.keySet()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("HEARTBEAT")
                        .data("{\"timestamp\":" + System.currentTimeMillis() + "}"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    public int getConnectedClientCount() {
        return emitters.size();
    }
}
