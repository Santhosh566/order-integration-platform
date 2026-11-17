package com.example.orders.controller;

import com.example.orders.service.OrderViews;
import com.example.orders.service.PartialCancelService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Changes to an order before it leaves the warehouse. The customer who placed it, or an admin.
 */
@RestController
@RequestMapping("/api/orders/{orderId}")
public class OrderAmendmentController {

    private final PartialCancelService partialCancel;
    private final OrderViews views;

    public OrderAmendmentController(PartialCancelService partialCancel, OrderViews views) {
        this.partialCancel = partialCancel;
        this.views = views;
    }

    /** Cancels some units; cancelling all that is left cancels the order. */
    @PostMapping("/cancel-items")
    public Map<String, Object> cancelItems(@PathVariable String orderId,
                                           @RequestBody PartialCancelService.CancelItems request) {
        PartialCancelService.Result result = partialCancel.cancelItems(orderId, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", views.forCurrentViewer(result.order()));
        body.put("creditNote", result.creditNote());
        body.put("wholeOrderCancelled", result.wholeOrderCancelled());
        body.put("refunded", result.refunded());
        return body;
    }
}
