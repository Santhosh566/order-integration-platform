package com.example.orders.controller;

import com.example.orders.delivery.DeliveryRatingService;
import com.example.orders.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer rates the delivery of their own order (customers only, see SecurityConfig). */
@RestController
@RequestMapping("/api/delivery-ratings")
public class DeliveryRatingController {

    private final DeliveryRatingService ratings;
    private final CurrentUser currentUser;

    public DeliveryRatingController(DeliveryRatingService ratings, CurrentUser currentUser) {
        this.ratings = ratings;
        this.currentUser = currentUser;
    }

    @GetMapping("/{orderId}")
    public DeliveryRatingService.Status status(@PathVariable String orderId) {
        return ratings.status(currentUser.require().userId(), orderId);
    }

    @PutMapping("/{orderId}")
    public DeliveryRatingService.Status rate(@PathVariable String orderId, @RequestBody DeliveryRatingService.Submit submit) {
        return ratings.rate(currentUser.require().userId(), orderId, submit);
    }
}
