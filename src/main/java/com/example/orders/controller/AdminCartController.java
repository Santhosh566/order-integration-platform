package com.example.orders.controller;

import com.example.orders.service.CartAdminService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Abandoned carts for the admin (admins only, SecurityConfig). */
@RestController
@RequestMapping("/api/admin/carts")
public class AdminCartController {

    private final CartAdminService carts;

    public AdminCartController(CartAdminService carts) {
        this.carts = carts;
    }

    /** Carts started from..to (dates, both included; the last 30 days when left out) and how they ended. */
    @GetMapping("/stats")
    public CartAdminService.Stats stats(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return carts.stats(from, to);
    }

    /** ?status=ACTIVE|ORDERED|RECOVERED|EMPTIED|EXPIRED, newest change first. */
    @GetMapping
    public CartAdminService.Page list(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size) {
        return carts.list(status, page, size);
    }
}
