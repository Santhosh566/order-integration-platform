package com.example.orders.controller;

import com.example.orders.security.CurrentUser;
import com.example.orders.service.GiftCardService;
import com.example.orders.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A customer's wallet: the balance, its history, and gift cards redeemed into it.
 */
@RestController
public class WalletController {

    public record Redeem(String code) {}

    private final WalletService wallets;
    private final GiftCardService giftCards;
    private final CurrentUser currentUser;

    public WalletController(WalletService wallets, GiftCardService giftCards, CurrentUser currentUser) {
        this.wallets = wallets;
        this.giftCards = giftCards;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/wallet")
    public WalletService.Wallet wallet(@RequestParam(defaultValue = "50") int limit) {
        return wallets.wallet(currentUser.require().userId(), limit);
    }

    @PostMapping("/api/wallet/gift-cards")
    public GiftCardService.Redeemed redeem(@RequestBody Redeem request) {
        return giftCards.redeem(currentUser.require().userId(), request == null ? null : request.code());
    }
}
