package com.example.orders.controller;

import com.example.orders.security.CurrentUser;
import com.example.orders.service.DeliveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.example.orders.delivery.DriverStatsService;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * What a delivery user does on the road. Each driver only sees and changes their own deliveries.
 */
@RestController
@RequestMapping("/api/delivery")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final DriverStatsService stats;
    private final CurrentUser currentUser;

    public DeliveryController(DeliveryService deliveryService, DriverStatsService stats, CurrentUser currentUser) {
        this.deliveryService = deliveryService;
        this.stats = stats;
        this.currentUser = currentUser;
    }

    /** The driver's own figures: today, this week, this month, this year. */
    @GetMapping("/stats")
    public DriverStatsService.Summary stats() {
        return stats.summary(driver());
    }

    @GetMapping("/stats/history")
    public DriverStatsService.History history(@RequestParam(defaultValue = "WEEK") DriverStatsService.Period period) {
        return stats.history(driver(), period);
    }

    @GetMapping("/available")
    public List<Map<String, Object>> available() {
        return deliveryService.available();
    }

    @GetMapping("/mine")
    public List<Map<String, Object>> mine() {
        return deliveryService.mine(driver());
    }

    @PostMapping("/orders/{orderId}/take")
    public Map<String, Object> take(@PathVariable String orderId) {
        return deliveryService.take(driver(), orderId);
    }

    @PostMapping("/orders/{orderId}/pickup")
    public Map<String, Object> pickUp(@PathVariable String orderId) {
        return deliveryService.pickUp(driver(), orderId);
    }

    @PostMapping("/orders/{orderId}/out-for-delivery")
    public Map<String, Object> outForDelivery(@PathVariable String orderId) {
        return deliveryService.outForDelivery(driver(), orderId);
    }

    @PostMapping("/orders/{orderId}/deliver")
    public Map<String, Object> deliver(@PathVariable String orderId, @RequestBody Map<String, Object> body) {
        Object code = body.get("code");
        // Cash on delivery: the driver confirms the cash was collected
        boolean cashCollected = Boolean.TRUE.equals(body.get("cashCollected"));
        return deliveryService.deliver(driver(), orderId, code == null ? null : code.toString(), cashCollected);
    }

    @PostMapping("/orders/{orderId}/fail")
    public Map<String, Object> fail(@PathVariable String orderId, @RequestBody Map<String, String> body) {
        return deliveryService.fail(driver(), orderId, body.get("reason"));
    }

    private String driver() {
        return currentUser.require().userId();
    }
}
