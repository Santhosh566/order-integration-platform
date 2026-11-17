package com.example.orders.route;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.FulfillmentEvent;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.OrderRepository;
import com.example.orders.service.CarrierTrackingService;
import com.example.orders.service.InventoryService;
import com.example.orders.service.InvoiceService;
import com.example.orders.service.OrderAccessService;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ClaimCheckOperation;
import org.apache.camel.processor.aggregate.jdbc.JdbcAggregationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 4: Async Aggregation, Claim Check & Fulfillment Orchestration
 *
 * EIPs implemented:
 *   1. Standalone Correlated Aggregator EIP:
 *      Correlates independent async events (PAYMENT_CAPTURED & WAREHOUSE_PACKED) by orderId.
 *      It completes only when both different signals arrived. State lives in PostgreSQL,
 *      and a group that never completes is discarded rather than released.
 *   2. Claim Check EIP:
 *      Offloads heavy binary invoice PDF to local File Vault (data/invoices/)
 *      and passes lightweight claim ticket in headers/body.
 *   3. Polling Consumer EIP:
 *      Simulates real-world carrier telemetry across delivery milestones:
 *      READY_FOR_DISPATCH -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED.
 *   4. Order Cancellation & Reverse Logistics Workflow:
 *      POST /api/orders/{id}/cancel coordinates compensating stock return & refund.
 */
@Component
public class FulfillmentRoute extends RouteBuilder {

    private final OrderFulfillmentAggregationStrategy aggregationStrategy;
    private final InvoiceService invoiceService;
    private final CarrierTrackingService carrierTrackingService;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final JdbcAggregationRepository fulfillmentAggregationRepository;
    private final long signalTimeoutMs;

    public FulfillmentRoute(OrderFulfillmentAggregationStrategy aggregationStrategy,
                            InvoiceService invoiceService,
                            CarrierTrackingService carrierTrackingService,
                            InventoryService inventoryService,
                            OrderRepository orderRepository,
                            JdbcAggregationRepository fulfillmentAggregationRepository,
                            @Value("${orders.fulfillment.signal-timeout-ms}") long signalTimeoutMs) {
        this.aggregationStrategy = aggregationStrategy;
        this.invoiceService = invoiceService;
        this.carrierTrackingService = carrierTrackingService;
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.fulfillmentAggregationRepository = fulfillmentAggregationRepository;
        this.signalTimeoutMs = signalTimeoutMs;
    }

    @Override
    public void configure() throws Exception {

        onException(InvalidOrderException.class)
            .handled(true)
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"REJECTED\",\"reason\":\"${exception.message}\"}"));

        onException(OrderNotFoundException.class)
            .handled(true)
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(constant("{\"status\":\"NOT_FOUND\",\"reason\":\"Order not found\"}"));

        // The order changed after the person opened it (If-Match): they reload and decide again
        onException(com.example.orders.exception.OrderChangedException.class)
            .handled(true)
            .log(LoggingLevel.INFO, "Request on an older copy: ${exception.message}")
            .process(OrderChangedReply.CHANGED);

        onException(org.springframework.orm.ObjectOptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Two changes to one order at once: ${exception.message}")
            .process(OrderChangedReply.CLASHED);

        onException(InvalidOrderStateException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Request refused: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(409))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"CONFLICT\",\"reason\":\"${exception.message}\"}"));

        // =========================================================================
        // 1. Asynchronous Inbound Webhooks for Fulfillment Signals
        // =========================================================================
        rest("/api/fulfillment")
            .post("/payment-captured")
                .consumes("application/json")
                .produces("application/json")
                .to("direct:ingestPaymentCaptured")
            .post("/warehouse-packed")
                .consumes("application/json")
                .produces("application/json")
                .to("direct:ingestWarehousePacked");

        from("direct:ingestPaymentCaptured")
            .routeId("ingest-payment-captured")
            .unmarshal().json(FulfillmentEvent.class)
            .process(exchange -> {
                FulfillmentEvent event = exchange.getMessage().getBody(FulfillmentEvent.class);
                if (event.getEventType() == null) {
                    event.setEventType("PAYMENT_CAPTURED");
                }
                requireAwaitingRelease(event);
            })
            .log(LoggingLevel.INFO, "[FULFILLMENT INBOUND] Received PAYMENT_CAPTURED for Order: ${body.orderId}")
            .to("direct:aggregateFulfillment")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"ACCEPTED\",\"event\":\"PAYMENT_CAPTURED\",\"orderId\":\"${header.orderId}\"}"));

        from("direct:ingestWarehousePacked")
            .routeId("ingest-warehouse-packed")
            .unmarshal().json(FulfillmentEvent.class)
            .process(exchange -> {
                FulfillmentEvent event = exchange.getMessage().getBody(FulfillmentEvent.class);
                if (event.getEventType() == null) {
                    event.setEventType("WAREHOUSE_PACKED");
                }
                requireAwaitingRelease(event);
            })
            .log(LoggingLevel.INFO, "[FULFILLMENT INBOUND] Received WAREHOUSE_PACKED for Order: ${body.orderId}")
            .setProperty("packedOrderId", simple("${body.orderId}"))
            .to("direct:aggregateFulfillment")
            // Cash on delivery: there is no capture to wait for, so the payment half is met here
            .filter(method(carrierTrackingService, "isCashOnDelivery(${exchangeProperty.packedOrderId})"))
                .process(exchange -> exchange.getMessage().setBody(new FulfillmentEvent(
                        exchange.getProperty("packedOrderId", String.class), "PAYMENT_CAPTURED", "system",
                        "Cash on delivery: collected at the door")))
                .to("direct:aggregateFulfillment")
            .end()
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"ACCEPTED\",\"event\":\"WAREHOUSE_PACKED\",\"orderId\":\"${header.orderId}\"}"));

        // =========================================================================
        // 2. Standalone Correlated Aggregator EIP
        // =========================================================================
        from("direct:aggregateFulfillment")
            .routeId("correlated-fulfillment-aggregator")
            .setHeader("orderId", simple("${body.orderId}"))
            .aggregate(header("orderId"), aggregationStrategy)
                .aggregationRepository(fulfillmentAggregationRepository)
                .optimisticLocking()
                // Both different signals are needed; a repeated signal cannot complete the group
                .completionPredicate(simple("${body.readyForDispatch} == true"))
                .completionTimeout(signalTimeoutMs)
                // Running out of time must never release an order
                .discardOnCompletionTimeout()
                .log(LoggingLevel.INFO,
                     "[CORRELATED AGGREGATOR TRIGGERED] Order ${header.orderId} has satisfied both Finance & Warehouse requirements! Releasing to shipping dock...")
                .to("direct:fulfillmentRelease");

        // =========================================================================
        // 3. Order Release, Invoice PDF Generation & Claim Check EIP
        // =========================================================================
        from("direct:fulfillmentRelease")
            .routeId("fulfillment-release-and-claimcheck")
            .setProperty("released", method(carrierTrackingService, "releaseToDispatch(${header.orderId})"))
            .filter(exchangeProperty("released").isEqualTo(false))
                .stop()
            .end()
            .log(LoggingLevel.INFO, "[ORDER STATUS] Order ${header.orderId} status set to READY_FOR_DISPATCH")

            // Generate PDF and obtain Claim Check ticket
            .process(exchange -> {
                String orderId = exchange.getMessage().getHeader("orderId", String.class);
                Map<String, Object> ticket = invoiceService.generateInvoiceAndClaimCheck(orderId);
                exchange.getMessage().setBody(ticket);
            })

            // EIP: Claim Check Push
            .claimCheck(ClaimCheckOperation.Push)
            .log(LoggingLevel.INFO,
                 "[CLAIM CHECK EIP] Stored heavy PDF in File Vault. Claim Ticket: ${body[claimTicket]}, Download: ${body[invoiceDownloadUrl]}");

        // =========================================================================
        // 4. Carrier Tracking Poller (Polling Consumer EIP)
        // =========================================================================
        from("timer:carrierTracking?period={{orders.tracking.poll-ms}}&delay={{orders.tracking.poll-ms}}")
            .routeId("carrier-tracking-poller")
            .bean(carrierTrackingService, "ordersToAutoRelease")
            .split(body())
                .setHeader("orderId", body())
                .to("direct:fulfillmentRelease")
            .end()
            .bean(carrierTrackingService, "advanceOrdersInTransit");

        // =========================================================================
        // 5. REST Invoice Download: the GST tax invoice as a PDF, the one copy of record
        // =========================================================================
        rest("/api/invoices/{orderId}")
            .get()
                .produces("application/pdf")
                .to("direct:downloadInvoice");

        from("direct:downloadInvoice")
            .routeId("download-invoice-rest")
            .bean(OrderAccessService.class, "requireOwnerOrAdmin(${header.orderId})")
            .process(exchange -> {
                String orderId = exchange.getMessage().getHeader("orderId", String.class);
                byte[] pdfBytes = invoiceService.getInvoicePdf(orderId);
                exchange.getMessage().setBody(pdfBytes);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/pdf");
                exchange.getMessage().setHeader("Content-Disposition", "inline; filename=invoice-" + orderId + ".pdf");
            });

        // =========================================================================
        // 6. Order Cancellation & Reverse Compensating Logistics
        // =========================================================================
        rest("/api/orders/{id}/cancel")
            .post()
                .produces("application/json")
                .to("direct:cancelOrderWorkflow");

        from("direct:cancelOrderWorkflow")
            .routeId("cancel-order-workflow")
            .setHeader("orderId", simple("${header.id}"))
            .bean(OrderAccessService.class, "requireOwnerOrAdmin(${header.orderId})")
            .process(exchange -> {
                String orderId = exchange.getMessage().getHeader("orderId", String.class);
                // The version the person saw, if they said: a cancel on an order that moved on is refused
                Long seen = com.example.orders.service.IfMatch.version(exchange.getMessage().getHeader("If-Match", String.class));
                Order order = inventoryService.cancel(orderId, seen);
                String outcome = order.getRefundedAt() != null ? "Stock restored and refund issued" : "Cancelled before payment";

                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
                exchange.getMessage().setHeader("ETag", com.example.orders.service.IfMatch.etag(order.getVersion()));
                exchange.getMessage().setBody("{\"status\":\"CANCELLED\",\"orderId\":\"" + orderId + "\",\"message\":\"" + outcome
                        + "\",\"version\":" + order.getVersion() + "}");
            });
    }

    /**
     * Signals only count for an order the carrier was booked for and that is still waiting.
     */
    private void requireAwaitingRelease(FulfillmentEvent event) {
        if (event.getOrderId() == null) {
            throw new InvalidOrderException("orderId is required");
        }
        Order order = orderRepository.findByOrderId(event.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException(event.getOrderId()));
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new InvalidOrderStateException("Order " + order.getOrderId() + " is " + order.getStatus()
                    + " and is not waiting for fulfillment signals");
        }
    }
}
