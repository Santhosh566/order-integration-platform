package com.example.orders.controller;

import com.example.orders.model.CreditNote;
import com.example.orders.model.ReturnRequest;
import com.example.orders.service.ReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Returns for customers: ask to send items back, and follow the request and its refund.
 * A customer sees only their own orders and returns; an admin sees all of them.
 */
@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final ReturnService returnService;

    public ReturnController(ReturnService returnService) {
        this.returnService = returnService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnRequest request(@RequestBody ReturnService.NewReturn request) {
        return returnService.request(request);
    }

    @GetMapping
    public List<ReturnRequest> list() {
        return returnService.visibleReturns();
    }

    @GetMapping("/{returnId}")
    public ReturnRequest get(@PathVariable String returnId) {
        return returnService.visibleReturn(returnId);
    }

    @GetMapping("/orders/{orderId}/credit-notes")
    public List<CreditNote> creditNotes(@PathVariable String orderId) {
        return returnService.creditNotes(orderId);
    }
}
