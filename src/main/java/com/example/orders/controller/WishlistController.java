package com.example.orders.controller;

import com.example.orders.service.WishlistService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The signed-in customer's wishlist (customers only, SecurityConfig). */
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    private final WishlistService wishlist;

    public WishlistController(WishlistService wishlist) {
        this.wishlist = wishlist;
    }

    @GetMapping
    public List<WishlistService.Entry> list() {
        return wishlist.list();
    }

    /** Saves the product; {"notifyWhenAvailable": true} also asks for the back-in-stock email. */
    @PutMapping("/{sku}")
    public WishlistService.Entry save(@PathVariable String sku,
                                      @RequestBody(required = false) WishlistService.Save request) {
        return wishlist.save(sku, request);
    }

    @DeleteMapping("/{sku}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String sku) {
        wishlist.remove(sku);
    }
}
