package com.example.orders.controller;

import com.example.orders.model.CreditNote;
import com.example.orders.model.ReturnRequest;
import com.example.orders.service.IfMatch;
import com.example.orders.service.ReturnService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Returns and refunds for staff: decide on requests, receive the goods, and goodwill refunds.
 * Admins only (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminReturnController {

    private final ReturnService returnService;

    public AdminReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    @GetMapping("/returns")
    public List<ReturnRequest> list(@RequestParam(required = false) ReturnRequest.Status status) {
        return returnService.byStatus(status);
    }

    @PostMapping("/returns/{returnId}/approve")
    public ReturnRequest approve(@PathVariable String returnId,
                                 @RequestBody(required = false) ReturnService.Decision decision) {
        return returnService.approve(returnId, decision);
    }

    @PostMapping("/returns/{returnId}/reject")
    public ReturnRequest reject(@PathVariable String returnId, @RequestBody ReturnService.Decision decision) {
        return returnService.reject(returnId, decision);
    }

    /** Body: {"conditions": {"SKU-1": "DAMAGED"}}; items not named are SELLABLE. */
    @PostMapping("/returns/{returnId}/receive")
    public CreditNote receive(@PathVariable String returnId,
                              @RequestBody(required = false) ReturnService.Inspection inspection) {
        return returnService.receive(returnId, inspection);
    }

    @PostMapping("/orders/{orderId}/refunds")
    public CreditNote goodwill(@PathVariable String orderId, @RequestBody ReturnService.GoodwillRefund request,
                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return returnService.goodwill(orderId, request, IfMatch.version(ifMatch));
    }
}
