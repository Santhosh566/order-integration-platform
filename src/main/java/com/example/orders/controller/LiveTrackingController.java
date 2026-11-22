package com.example.orders.controller;

import com.example.orders.delivery.LiveTrackingService;
import com.example.orders.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Live tracking: drivers send positions (/api/delivery/**, drivers only), customers and admins read the map of an
 * order (/api/orders/**, its customer or an admin), admins see every driver out (/api/admin/**). See SecurityConfig.
 */
@RestController
public class LiveTrackingController {

    private final LiveTrackingService tracking;
    private final CurrentUser currentUser;

    public LiveTrackingController(LiveTrackingService tracking, CurrentUser currentUser) {
        this.tracking = tracking;
        this.currentUser = currentUser;
    }

    /** {"latitude": 12.97, "longitude": 77.64, "accuracyM": 12, "speedMps": 6.1, "headingDeg": 90, "recordedAt": "..."} */
    @PostMapping("/api/delivery/location")
    public LiveTrackingService.Received location(@RequestBody LiveTrackingService.Report report) {
        return tracking.record(currentUser.require().userId(), report);
    }

    @GetMapping("/api/orders/{orderId}/tracking")
    public LiveTrackingService.Tracking order(@PathVariable String orderId) {
        return tracking.tracking(orderId);
    }

    @GetMapping("/api/admin/delivery/locations")
    public List<LiveTrackingService.DriverOnRoad> drivers() {
        return tracking.driversOnRoad();
    }
}
