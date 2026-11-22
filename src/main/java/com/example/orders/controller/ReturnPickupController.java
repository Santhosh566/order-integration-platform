package com.example.orders.controller;

import com.example.orders.model.ReturnPickup;
import com.example.orders.security.CurrentUser;
import com.example.orders.service.ReturnPickupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Collecting approved returns. Drivers use /api/delivery/pickups (DELIVERY role), admins
 * /api/admin/pickups (ADMIN role); see SecurityConfig.
 */
@RestController
public class ReturnPickupController {

    private final ReturnPickupService pickups;
    private final CurrentUser currentUser;

    public ReturnPickupController(ReturnPickupService pickups, CurrentUser currentUser) {
        this.pickups = pickups;
        this.currentUser = currentUser;
    }

    // ── Driver ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/delivery/pickups/available")
    public List<Map<String, Object>> available() {
        return pickups.available(driver());
    }

    @GetMapping("/api/delivery/pickups/mine")
    public List<Map<String, Object>> mine() {
        return pickups.mine(driver());
    }

    @PostMapping("/api/delivery/pickups/{returnId}/take")
    public Map<String, Object> take(@PathVariable String returnId) {
        return pickups.take(driver(), returnId);
    }

    @PostMapping("/api/delivery/pickups/{returnId}/start")
    public Map<String, Object> start(@PathVariable String returnId) {
        return pickups.start(driver(), returnId);
    }

    @PostMapping("/api/delivery/pickups/{returnId}/collect")
    public Map<String, Object> collect(@PathVariable String returnId, @RequestBody Map<String, String> body) {
        return pickups.collect(driver(), returnId, body.get("code"));
    }

    @PostMapping("/api/delivery/pickups/{returnId}/fail")
    public Map<String, Object> fail(@PathVariable String returnId, @RequestBody Map<String, String> body) {
        return pickups.fail(driver(), returnId, body.get("reason"));
    }

    // ── Admin ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/pickups/waiting")
    public List<Map<String, Object>> waiting() {
        return pickups.available();
    }

    @GetMapping("/api/admin/pickups/in-progress")
    public List<Map<String, Object>> inProgress() {
        return pickups.inProgress();
    }

    @PostMapping("/api/admin/pickups/{returnId}/assign")
    public Map<String, Object> assign(@PathVariable String returnId, @RequestBody Map<String, String> body) {
        return pickups.assign(returnId, body.get("deliveryUserId"));
    }

    @GetMapping("/api/admin/pickups/{returnId}/attempts")
    public List<ReturnPickup> attempts(@PathVariable String returnId) {
        return pickups.attempts(returnId);
    }

    private String driver() {
        return currentUser.require().userId();
    }
}
