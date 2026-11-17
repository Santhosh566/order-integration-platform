package com.example.orders.controller;

import com.example.orders.security.CurrentUser;
import com.example.orders.service.GiftCardService;
import com.example.orders.service.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gift cards (issue, list, stop) and customers' wallets (look, give or take back store credit), for admins.
 */
@RestController
public class AdminWalletController {

    public record VoidRequest(String reason) {}

    private final WalletService wallets;
    private final GiftCardService giftCards;
    private final CurrentUser currentUser;

    public AdminWalletController(WalletService wallets, GiftCardService giftCards, CurrentUser currentUser) {
        this.wallets = wallets;
        this.giftCards = giftCards;
        this.currentUser = currentUser;
    }

    /** Issues a batch. The reply holds the full codes: they are not shown again. */
    @PostMapping("/api/admin/gift-cards")
    @ResponseStatus(HttpStatus.CREATED)
    public GiftCardService.Batch issue(@RequestBody GiftCardService.NewBatch request) {
        return giftCards.issue(request, currentUser.require().userId());
    }

    @GetMapping("/api/admin/gift-cards")
    public List<GiftCardService.Card> cards(@RequestParam(required = false) GiftCardService.Status status,
                                            @RequestParam(defaultValue = "200") int limit) {
        return giftCards.cards(status, limit);
    }

    @PostMapping("/api/admin/gift-cards/{id}/void")
    public GiftCardService.Card voidCard(@PathVariable long id, @RequestBody VoidRequest request) {
        return giftCards.voidCard(id, request == null ? null : request.reason(), currentUser.require().userId());
    }

    @GetMapping("/api/admin/users/{userId}/wallet")
    public WalletService.Wallet wallet(@PathVariable String userId, @RequestParam(defaultValue = "50") int limit) {
        return wallets.wallet(userId, limit);
    }

    @PostMapping("/api/admin/users/{userId}/wallet/credits")
    public WalletService.Entry credit(@PathVariable String userId, @RequestBody WalletService.Adjustment adjustment) {
        return wallets.adminCredit(userId, adjustment, currentUser.require().userId());
    }

    @PostMapping("/api/admin/users/{userId}/wallet/debits")
    public WalletService.Entry debit(@PathVariable String userId, @RequestBody WalletService.Adjustment adjustment) {
        return wallets.adminDebit(userId, adjustment, currentUser.require().userId());
    }
}
