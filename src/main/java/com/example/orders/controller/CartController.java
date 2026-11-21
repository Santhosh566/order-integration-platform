package com.example.orders.controller;

import com.example.orders.service.CartService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The signed-in customer's cart (customers only, SecurityConfig). */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @GetMapping
    public CartService.Cart mine() {
        return carts.mine();
    }

    /** Replaces the lines: [{"sku": "...", "quantity": 2}]; an empty list empties the cart. */
    @PutMapping
    public CartService.Cart replace(@RequestBody List<CartService.Line> lines) {
        return carts.replace(lines);
    }

    /** Adds a cart this browser kept on its own; a product in both keeps the larger quantity. */
    @PostMapping("/merge")
    public CartService.Cart merge(@RequestBody List<CartService.Line> lines) {
        return carts.merge(lines);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        carts.clear();
    }
}
