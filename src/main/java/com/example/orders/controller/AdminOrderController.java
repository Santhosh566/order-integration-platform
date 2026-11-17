package com.example.orders.controller;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The order list for staff: search, filter by status, one page at a time, newest first.
 * Admins only (see SecurityConfig). One order is read with GET /api/orders/{id}.
 */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderRepository orders;

    public AdminOrderController(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * @param status one or more statuses, comma separated; empty means all
     * @param q      part of an order id, customer id, the customer's reference or an invoice number
     */
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> search(@RequestParam(defaultValue = "") String status,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size) {
        String text = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<Order> result = orders.search(statuses(status), text,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("orderId"))));

        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("items", result.getContent().stream().map(AdminOrderController::row).toList());
        reply.put("page", result.getNumber());
        reply.put("size", result.getSize());
        reply.put("totalItems", result.getTotalElements());
        reply.put("totalPages", result.getTotalPages());
        return reply;
    }

    /** How many orders are in each status, for the filter and the dashboard. Statuses with none are 0. */
    @GetMapping("/status-counts")
    @Transactional(readOnly = true)
    public Map<OrderStatus, Long> statusCounts() {
        Map<OrderStatus, Long> counts = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) {
            counts.put(s, 0L);
        }
        for (Object[] row : orders.countByStatus()) {
            counts.put((OrderStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static Set<OrderStatus> statuses(String status) {
        if (status.isBlank()) {
            return EnumSet.allOf(OrderStatus.class);
        }
        try {
            Set<OrderStatus> chosen = EnumSet.noneOf(OrderStatus.class);
            Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> OrderStatus.valueOf(s.toUpperCase(Locale.ROOT))).forEach(chosen::add);
            return chosen;
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderException("status must be one or more of " + Arrays.toString(OrderStatus.values()));
        }
    }

    private static Map<String, Object> row(Order order) {
        ShippingAddress address = order.getShippingAddress();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", order.getOrderId());
        row.put("customerId", order.getCustomerId());
        row.put("recipientName", address != null ? address.getRecipientName() : null);
        row.put("city", address != null ? address.getCity() : null);
        row.put("state", address != null ? address.getState() : null);
        row.put("status", order.getStatus());
        row.put("orderType", order.getOrderType());
        row.put("currency", order.getCurrency());
        row.put("total", order.getTotal());
        row.put("refundedTotal", order.getRefundedTotal());
        row.put("itemCount", order.getItems().stream().mapToInt(i -> i.getActiveQuantity()).sum());
        row.put("fraudStatus", order.getFraudStatus());
        row.put("returnStatus", order.getReturnStatus());
        row.put("invoiceNumber", order.getInvoiceNumber());
        row.put("externalReference", order.getExternalReference());
        row.put("createdAt", order.getCreatedAt());
        row.put("statusChangedAt", order.getStatusChangedAt());
        return row;
    }
}
