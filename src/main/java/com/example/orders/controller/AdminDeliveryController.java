package com.example.orders.controller;

import com.example.orders.model.DeliveryAssignment;
import com.example.orders.model.Order;
import com.example.orders.service.DeliveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.example.orders.delivery.DriverStatsService;

import java.util.List;
import java.util.Map;

/**
 * Operations on deliveries: who takes an order, its attempt history, and giving up on it.
 * Admins only (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDeliveryController {

    private final DeliveryService deliveryService;
    private final DriverStatsService stats;

    public AdminDeliveryController(DeliveryService deliveryService, DriverStatsService stats) {
        this.deliveryService = deliveryService;
        this.stats = stats;
    }

    /** Every driver's figures for the period so far. */
    @GetMapping("/delivery/drivers/stats")
    public DriverStatsService.Leaderboard driverStats(@RequestParam(defaultValue = "MONTH") DriverStatsService.Period period) {
        return stats.leaderboard(period);
    }

    @GetMapping("/delivery/drivers/{driverId}/stats")
    public DriverStatsService.Summary driverStats(@PathVariable String driverId) {
        return stats.summary(driverId);
    }

    @GetMapping("/delivery/drivers/{driverId}/stats/history")
    public DriverStatsService.History driverHistory(@PathVariable String driverId,
                                                    @RequestParam(defaultValue = "WEEK") DriverStatsService.Period period) {
        return stats.history(driverId, period);
    }

    @GetMapping("/delivery/waiting")
    public List<Map<String, Object>> waiting() {
        return deliveryService.available();
    }

    @GetMapping("/delivery/in-progress")
    public List<Map<String, Object>> inProgress() {
        return deliveryService.inProgress();
    }

    @GetMapping("/delivery/drivers")
    public List<Map<String, Object>> drivers() {
        return deliveryService.drivers();
    }

    @PostMapping("/delivery/orders/{orderId}/assign")
    public Map<String, Object> assign(@PathVariable String orderId, @RequestBody Map<String, String> body) {
        return deliveryService.assign(orderId, body.get("deliveryUserId"));
    }

    @GetMapping("/delivery/orders/{orderId}/attempts")
    public List<DeliveryAssignment> attempts(@PathVariable String orderId) {
        return deliveryService.attempts(orderId);
    }

    @PostMapping("/orders/{orderId}/return")
    public Map<String, Object> returnToStock(@PathVariable String orderId) {
        Order order = deliveryService.returnToStock(orderId);
        return Map.of("status", order.getStatus(), "orderId", order.getOrderId());
    }
}
