package com.example.orders.controller;

import com.example.orders.model.Role;
import com.example.orders.service.AdminUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admins look up users (customers, drivers and staff). /api/admin/** is admin-only in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService users;
    private final com.example.orders.service.AccountStatusService accounts;

    public AdminUserController(AdminUserService users, com.example.orders.service.AccountStatusService accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    /** Body: {"reason": "..."}. Customers and drivers only; a driver's unstarted jobs go back on the list. */
    @org.springframework.web.bind.annotation.PostMapping("/{userId}/deactivate")
    public com.example.orders.service.AccountStatusService.Result deactivate(
            @PathVariable String userId,
            @org.springframework.web.bind.annotation.RequestBody com.example.orders.service.AccountStatusService.Deactivate request) {
        return accounts.deactivate(userId, request);
    }

    @org.springframework.web.bind.annotation.PostMapping("/{userId}/activate")
    public com.example.orders.service.AccountStatusService.Result activate(@PathVariable String userId) {
        return accounts.activate(userId);
    }

    @GetMapping
    public AdminUserService.UserPage search(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) Role role,
                                            @RequestParam(required = false) Boolean active,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        return users.search(q, role, active, page, size);
    }

    @GetMapping("/{userId}")
    public AdminUserService.UserDetail detail(@PathVariable String userId) {
        return users.detail(userId);
    }
}
