package com.example.orders.controller;

import com.example.orders.model.InventoryItem;
import com.example.orders.security.CurrentUser;
import com.example.orders.service.OrderEventBroadcaster;
import com.example.orders.service.RouteManagementService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Phase 5 REST Controller
 * Serves real-time Server-Sent Events (SSE), ControlBus route management,
 * and executive dashboard telemetry for the web interface.
 */
@RestController
@RequestMapping("/api")
public class PlatformDashboardController {

    private final OrderEventBroadcaster eventBroadcaster;
    private final RouteManagementService routeManagementService;
    private final CurrentUser currentUser;

    public PlatformDashboardController(OrderEventBroadcaster eventBroadcaster,
                                       RouteManagementService routeManagementService,
                                       CurrentUser currentUser) {
        this.eventBroadcaster = eventBroadcaster;
        this.routeManagementService = routeManagementService;
        this.currentUser = currentUser;
    }

    /**
     * Real-time Server-Sent Events (SSE) stream.
     * Connected browsers listen on this endpoint for instant updates.
     */
    @GetMapping(value = "/stream/orders", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOrders() {
        return eventBroadcaster.subscribe(currentUser.require());
    }

    /**
     * Lists all registered Camel routes and their current status.
     */
    @GetMapping("/admin/routes")
    public ResponseEntity<List<Map<String, Object>>> getRoutes() {
        return ResponseEntity.ok(routeManagementService.getRouteDetails());
    }

    /**
     * Executes ControlBus action on a route (start, stop, suspend, resume).
     */
    @PostMapping("/admin/routes/{routeId}/{action}")
    public ResponseEntity<Map<String, Object>> executeRouteAction(@PathVariable String routeId,
                                                                 @PathVariable String action) {
        return ResponseEntity.ok(routeManagementService.executeRouteAction(routeId, action));
    }

    /**
     * Retrieves live stock levels from PostgreSQL inventory table.
     */
    @GetMapping("/admin/inventory")
    public ResponseEntity<List<InventoryItem>> getInventory() {
        return ResponseEntity.ok(routeManagementService.getInventoryItems());
    }

    /**
     * Retrieves executive summary KPIs (revenue, total orders, DLQ count, active routes).
     */
    @GetMapping("/admin/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(routeManagementService.getPlatformMetrics());
    }
}
