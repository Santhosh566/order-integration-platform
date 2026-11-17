package com.example.orders.controller;

import com.example.orders.service.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reviews: customers write their own (/api/reviews), admins moderate (/api/admin/reviews). The shop
 * reads the published ones through GraphQL (Product.reviews).
 */
@RestController
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/reviews")
    public ReviewService.Mine mine() {
        return reviews.mine();
    }

    @GetMapping("/api/reviews/{sku}")
    public ReviewService.ForProduct forProduct(@PathVariable String sku) {
        return reviews.forProduct(sku);
    }

    @PutMapping("/api/reviews/{sku}")
    public ReviewService.Review submit(@PathVariable String sku, @RequestBody ReviewService.Submit request) {
        return reviews.submit(sku, request);
    }

    @DeleteMapping("/api/reviews/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String sku) {
        reviews.delete(sku);
    }

    @GetMapping("/api/admin/reviews")
    public List<ReviewService.Review> queue(@RequestParam(required = false) ReviewService.Status status) {
        return reviews.byStatus(status);
    }

    @PostMapping("/api/admin/reviews/{id}/approve")
    public ReviewService.Review approve(@PathVariable long id) {
        return reviews.approve(id);
    }

    @PostMapping("/api/admin/reviews/{id}/reject")
    public ReviewService.Review reject(@PathVariable long id, @RequestBody ReviewService.Decision decision) {
        return reviews.reject(id, decision);
    }
}
