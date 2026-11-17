package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.security.CurrentUser;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import com.example.orders.money.Money;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.DeadLetterEventRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.OrderRepository;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5: Operations & ControlBus Administration Service
 *
 * Interfaces with CamelContext and RouteController to provide live telemetry,
 * dynamic route management, inventory monitors, and aggregate system metrics.
 */
@Service
public class RouteManagementService {

    private static final Logger log = LoggerFactory.getLogger(RouteManagementService.class);

    private final CamelContext camelContext;
    private final OrderRepository orderRepository;
    private final InventoryRepository inventoryRepository;
    private final DeadLetterEventRepository deadLetterEventRepository;
    private final OrderEventBroadcaster eventBroadcaster;
    private final CurrentUser currentUser;
    private final RouteBacklogService backlog;

    @Value("${orders.currency:INR}")
    private String currency;

    public RouteManagementService(CamelContext camelContext,
                                  OrderRepository orderRepository,
                                  InventoryRepository inventoryRepository,
                                  DeadLetterEventRepository deadLetterEventRepository,
                                  OrderEventBroadcaster eventBroadcaster,
                                  CurrentUser currentUser,
                                  RouteBacklogService backlog) {
        this.camelContext = camelContext;
        this.orderRepository = orderRepository;
        this.inventoryRepository = inventoryRepository;
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.eventBroadcaster = eventBroadcaster;
        this.currentUser = currentUser;
        this.backlog = backlog;
    }

    /**
     * Retrieves all registered Camel routes and their live operational status.
     */
    public List<Map<String, Object>> getRouteDetails() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Route route : camelContext.getRoutes()) {
            Map<String, Object> info = new LinkedHashMap<>();
            String routeId = route.getRouteId();
            ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
            String uri = route.getEndpoint() != null ? route.getEndpoint().getEndpointUri() : "";

            info.put("routeId", routeId);
            info.put("status", status != null ? status.name() : "UNKNOWN");
            info.put("kind", kindOf(uri));
            info.put("controllable", isControllable(uri));
            info.put("endpointUri", uri);
            info.put("description", route.getDescription());
            info.put("uptime", route.getUptime());
            // Messages being worked on right now; stopping waits for them
            info.put("inflight", camelContext.getInflightRepository().size(routeId));
            // What a pause holds back: orders, emails or files waiting for this consumer
            info.put("waiting", isControllable(uri) ? backlog.backlogFor(routeId) : null);
            result.add(info);
        }
        result.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("kind"))
                .thenComparing(r -> (String) r.get("routeId")));
        return result;
    }

    /** Where messages enter: CONSUMER (Kafka, files, timers), API (REST) or INTERNAL (direct: steps). */
    static String kindOf(String endpointUri) {
        String scheme = endpointUri.contains(":") ? endpointUri.substring(0, endpointUri.indexOf(':')) : endpointUri;
        if (CONSUMER_SCHEMES.contains(scheme)) {
            return "CONSUMER";
        }
        return scheme.equals("rest") || scheme.equals("platform-http") ? "API" : "INTERNAL";
    }

    /**
     * Only consumers may be paused: their messages wait (in Kafka, the inbox folder, the outbox
     * table) and are picked up on resume. Stopping an API route breaks the API, including saga
     * compensation and these controls; stopping an internal step breaks orders half way through.
     */
    static boolean isControllable(String endpointUri) {
        return "CONSUMER".equals(kindOf(endpointUri));
    }

    private static final Set<String> CONSUMER_SCHEMES = Set.of("kafka", "file", "timer", "scheduler", "quartz", "sftp", "ftp");

    /**
     * Executes a ControlBus lifecycle action on a Camel route.
     * Actions: start, stop, suspend, resume
     */
    public Map<String, Object> executeRouteAction(String routeId, String action) {
        Route route = camelContext.getRoute(routeId);
        if (route == null) {
            throw new NotFoundException("Route " + routeId + " not found");
        }
        if (!Set.of("start", "stop", "suspend", "resume").contains(action.toLowerCase())) {
            throw new InvalidOrderException("action must be start, stop, suspend or resume");
        }
        String uri = route.getEndpoint() != null ? route.getEndpoint().getEndpointUri() : "";
        if (!isControllable(uri)) {
            throw new InvalidOrderStateException("Route " + routeId + " is part of the " + kindOf(uri).toLowerCase()
                    + " and cannot be stopped from here; only consumers (Kafka, files, timers) can be paused");
        }
        String operator = currentUser.require().userId();
        Map<String, Object> response = new HashMap<>();
        response.put("routeId", routeId);
        response.put("action", action);

        try {
            switch (action.toLowerCase()) {
                case "start":
                    camelContext.getRouteController().startRoute(routeId);
                    break;
                case "stop":
                    camelContext.getRouteController().stopRoute(routeId);
                    break;
                case "suspend":
                    camelContext.getRouteController().suspendRoute(routeId);
                    break;
                case "resume":
                    camelContext.getRouteController().resumeRoute(routeId);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported ControlBus action: " + action);
            }

            ServiceStatus newStatus = camelContext.getRouteController().getRouteStatus(routeId);
            response.put("status", newStatus != null ? newStatus.name() : "UNKNOWN");
            response.put("success", true);

            log.warn("[CONTROLBUS ACTION] {} ran {} on route '{}', now {}", operator, action, routeId, response.get("status"));
            response.put("by", operator);

            // Broadcast change to all connected UI clients
            eventBroadcaster.broadcast("ROUTE_UPDATED", response);

        } catch (Exception e) {
            log.error("[CONTROLBUS ERROR] {} failed to {} route {}: {}", operator, action, routeId, e.getMessage());
            throw new InvalidOrderStateException("Could not " + action + " route " + routeId + ": " + e.getMessage());
        }

        return response;
    }

    /**
     * Retrieves live warehouse stock inventory.
     */
    public List<InventoryItem> getInventoryItems() {
        return inventoryRepository.findAll();
    }

    /**
     * Calculates aggregate executive metrics for the Operations dashboard.
     */
    public Map<String, Object> getPlatformMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        long totalOrders = orderRepository.count();
        // Only the platform currency: older orders in another currency cannot be added to it
        BigDecimal totalRevenue = orderRepository.sumTotalByStatusInAndCurrency(OrderStatus.revenueStatuses(), currency);

        // Only entries still waiting for an operator; replayed and dismissed ones are history
        long dlqCount = deadLetterEventRepository.countByReplayedFalseAndDismissedAtIsNull();

        long activeRoutes = camelContext.getRoutes().stream()
                .filter(r -> {
                    ServiceStatus s = camelContext.getRouteController().getRouteStatus(r.getRouteId());
                    return s != null && s.isStarted();
                })
                .count();

        metrics.put("totalOrders", totalOrders);
        metrics.put("totalRevenue", Money.of(totalRevenue));
        metrics.put("currency", currency);
        metrics.put("dlqQuarantinedCount", dlqCount);
        metrics.put("activeRoutesCount", activeRoutes);
        metrics.put("totalRoutesCount", camelContext.getRoutes().size());
        metrics.put("connectedUiClients", eventBroadcaster.getConnectedClientCount());

        return metrics;
    }
}
