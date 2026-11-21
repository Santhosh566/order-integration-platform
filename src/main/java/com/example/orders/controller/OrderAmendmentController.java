package com.example.orders.controller;

import com.example.orders.model.OrderRequest;
import com.example.orders.service.IfMatch;
import com.example.orders.service.OrderChangeService;
import com.example.orders.service.OrderViews;
import com.example.orders.service.PartialCancelService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes to an order after it was placed. The customer who placed it, or an admin. Each action takes the
 * order version the person saw in If-Match and is refused with 412 when the order changed since.
 */
@RestController
@RequestMapping("/api/orders/{orderId}")
public class OrderAmendmentController {

    private final PartialCancelService partialCancel;
    private final OrderChangeService changes;
    private final OrderViews views;

    public OrderAmendmentController(PartialCancelService partialCancel, OrderChangeService changes, OrderViews views) {
        this.partialCancel = partialCancel;
        this.changes = changes;
        this.views = views;
    }

    /** Cancels some units; cancelling all that is left cancels the order. */
    @PostMapping("/cancel-items")
    public Map<String, Object> cancelItems(@PathVariable String orderId,
                                           @RequestBody PartialCancelService.CancelItems request,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        PartialCancelService.Result result = partialCancel.cancelItems(orderId, request, IfMatch.version(ifMatch));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", views.forCurrentViewer(result.order()));
        body.put("creditNote", result.creditNote());
        body.put("wholeOrderCancelled", result.wholeOrderCancelled());
        body.put("refunded", result.refunded());
        return body;
    }

    /** A new delivery address, until a driver takes the parcel. */
    @PostMapping("/address")
    public Map<String, Object> changeAddress(@PathVariable String orderId,
                                             @RequestBody OrderChangeService.AddressChange request,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return reply(changes.changeAddress(orderId, request, IfMatch.version(ifMatch)));
    }

    /** Another delivery day and time; {"date": null, "window": null} is standard delivery. */
    @PostMapping("/delivery-slot")
    public Map<String, Object> changeSlot(@PathVariable String orderId,
                                          @RequestBody OrderRequest.DeliverySlot slot,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return reply(changes.changeSlot(orderId, slot, IfMatch.version(ifMatch)));
    }

    /** More items, until the order is packed; paid now, or added to the cash due at the door. */
    @PostMapping("/items")
    public Map<String, Object> addItems(@PathVariable String orderId,
                                        @RequestBody OrderChangeService.AddItems request,
                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return reply(changes.addItems(orderId, request, IfMatch.version(ifMatch)));
    }

    /** Who changed what on the order, oldest first. */
    @GetMapping("/changes")
    public List<OrderChangeService.Change> history(@PathVariable String orderId) {
        return changes.changes(orderId);
    }

    private Map<String, Object> reply(OrderChangeService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", views.forCurrentViewer(result.order()));
        body.put("changes", result.changes());
        body.put("payment", result.payment());
        return body;
    }
}
