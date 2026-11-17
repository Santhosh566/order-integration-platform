package com.example.orders.controller;

import com.example.orders.security.CurrentUser;
import com.example.orders.service.PromotionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Promotions (shop banners). Admins manage them under /api/admin/promotions; the shop reads the live
 * ones from /api/public/promotions, and signed-in shoppers report views and clicks.
 */
@RestController
public class PromotionController {

    private final PromotionService promotions;
    private final CurrentUser currentUser;

    public PromotionController(PromotionService promotions, CurrentUser currentUser) {
        this.promotions = promotions;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/public/promotions")
    public List<PromotionService.Banner> banners() {
        return promotions.banners();
    }

    @PostMapping("/api/promotions/{id}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void viewed(@PathVariable long id) {
        promotions.viewed(id);
    }

    @PostMapping("/api/promotions/{id}/click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clicked(@PathVariable long id) {
        promotions.clicked(id);
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    @GetMapping("/api/admin/promotions")
    public List<PromotionService.Promotion> list() {
        return promotions.list();
    }

    @GetMapping("/api/admin/promotions/{id}")
    public PromotionService.Promotion get(@PathVariable long id) {
        return promotions.get(id);
    }

    @PostMapping("/api/admin/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    public PromotionService.Promotion create(@RequestBody PromotionService.PromotionRequest request) {
        return promotions.create(request, admin());
    }

    @PutMapping("/api/admin/promotions/{id}")
    public PromotionService.Promotion update(@PathVariable long id, @RequestBody PromotionService.PromotionRequest request) {
        return promotions.update(id, request, admin());
    }

    /** Body: {"active": false}. */
    @PatchMapping("/api/admin/promotions/{id}")
    public PromotionService.Promotion setActive(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) {
            throw new com.example.orders.exception.InvalidOrderException("Send {\"active\": true} or {\"active\": false}");
        }
        return promotions.setActive(id, active, admin());
    }

    @DeleteMapping("/api/admin/promotions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        promotions.delete(id, admin());
    }

    /** multipart/form-data "file": a wide picture; the middle 3:1 strip is kept, 1500 × 500 pixels. */
    @PostMapping(value = "/api/admin/promotions/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PromotionService.Promotion image(@PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
        return promotions.setImage(id, file.getBytes(), admin());
    }

    @DeleteMapping("/api/admin/promotions/{id}/image")
    public PromotionService.Promotion removeImage(@PathVariable long id) {
        return promotions.removeImage(id, admin());
    }

    private String admin() {
        return currentUser.require().userId();
    }
}
