package com.example.orders.route;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderRequest;
import com.example.orders.service.CatalogService;
import com.example.orders.service.DeadLetterService;
import com.example.orders.service.OrderAccessService;
import com.example.orders.service.OrderService;
import com.example.orders.service.OrderValidator;
import com.example.orders.service.OrderViews;
import tools.jackson.core.JacksonException;
import com.jayway.jsonpath.InvalidJsonException;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.processor.PredicateValidationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Step 4 & Phase 1:
 *   - REST DSL: Order management endpoints & Dead Letter Queue (DLQ) inspection/replay
 *   - Phase 1.4: Transactional Client (.transacted()) ensuring atomicity across DB writes
 *   - EIPs: Idempotent Consumer, Content-Based Router, Message Filter, Validate
 */
@Component
public class OrderRoute extends RouteBuilder {

    private final IdempotentRepository orderIdRepository;

    public OrderRoute(@Qualifier("orderIdRepository") IdempotentRepository orderIdRepository) {
        this.orderIdRepository = orderIdRepository;
    }

    @Override
    public void configure() throws Exception {

        // Exception handling: a failed validate() answers 400 with the rule that was broken
        onException(PredicateValidationException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Order rejected: ${exchangeProperty.rule}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"REJECTED\",\"reason\":\"${exchangeProperty.rule}\"}"));

        onException(InvalidOrderException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Order rejected: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"REJECTED\",\"reason\":\"${exception.message}\"}"));

        onException(InvalidJsonException.class, JacksonException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Order rejected: body is not valid JSON: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(constant("{\"status\":\"REJECTED\",\"reason\":\"Request body is not a valid JSON order\"}"));

        onException(OrderNotFoundException.class)
            .handled(true)
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(constant("{\"status\":\"NOT_FOUND\",\"reason\":\"Order not found\"}"));

        // A dead letter that does not exist, or an id that is not a number
        onException(NotFoundException.class, NumberFormatException.class)
            .handled(true)
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(constant("{\"status\":\"NOT_FOUND\",\"reason\":\"Not found\"}"));

        onException(InvalidOrderStateException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Request refused: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(409))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"CONFLICT\",\"reason\":\"${exception.message}\"}"));

        // REST DSL served by the platform-http component on the Spring Boot web server
        restConfiguration()
            .component("platform-http")
            // Newer Camel merges a verb's direct: route into the REST route by default, which
            // removes the direct: endpoint. Other routes also send to direct:createOrder, so keep them
            .inlineRoutes(false);

        rest("/api/orders")
            .post()
                .consumes("application/json")
                .produces("application/json")
                .to("direct:createOrder")
            .get()
                .produces("application/json")
                .to("direct:listOrders")
            .get("/{id}")
                .produces("application/json")
                .to("direct:getOrder")
            .patch("/{id}/status")
                .consumes("application/json")
                .produces("application/json")
                .to("direct:updateOrderStatus")
            .delete("/{id}")
                .produces("application/json")
                .to("direct:deleteOrder");

        // Phase 1.1: Dead Letter Queue (DLQ) Management Endpoints
        rest("/api/dlq")
            .get()
                .produces("application/json")
                .to("direct:listDlq")
            .post("/{id}/replay")
                .produces("application/json")
                .to("direct:replayDlq")
            .post("/{id}/dismiss")
                .consumes("application/json")
                .produces("application/json")
                .to("direct:dismissDlq");

        // Direct component: synchronous hand-off from the REST endpoint
        from("direct:createOrder")
            .routeId("create-order")
            .convertBodyTo(String.class)

            // EIP: Validate + Expression: Simple. The "rule" property names the check for the error reply.
            .setProperty("rule", constant("Request body is required"))
            .validate().simple("${body} != null && ${body} != ''")

            // EIP: Message Translator - JSON text to the request, which holds only the fields a caller may set
            .unmarshal().json(JsonLibrary.Jackson, OrderRequest.class)
            // The server issues the id; the caller's orderId is kept only as their reference
            .process(OrderRoute::toNewOrder)

            // A customer orders for themselves; only an admin names the customer
            .bean(OrderAccessService.class, "assignCustomer")
            .setProperty("rule", constant("customerId is required"))
            .validate().simple("${body.customerId} != null")
            .setHeader("customerId", simple("${body.customerId}"))

            // EIP: Message Filter - test orders are answered here and go no further
            .filter(simple("${body.externalReference} startsWith 'TEST-'"))
                .log("Test order ${body.externalReference} ignored")
                .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                .setBody(simple("{\"status\":\"IGNORED\",\"orderReference\":\"${body.externalReference}\"}"))
                .stop()
            .end()

            .bean(OrderValidator.class, "validate")
            .to("direct:processOrder");

        from("direct:processOrder")
            .routeId("process-order")
            // Phase 1.4: EIP Transactional Client — coordinates Spring DB transaction across route
            .transacted()

            // Catalogue prices first: the value decides the order type and the fraud check
            .bean(CatalogService.class, "applyPrices")
            // Cash on delivery and the delivery slot, which need the priced total and hold the slot's lock
            .bean(com.example.orders.service.CheckoutService.class, "checkOptions")

            // EIP: Content-Based Router - classify the order by its value
            .choice()
                .when(simple("${body.total} > {{orders.pricing.high-value-above}}"))
                    .setHeader("orderType", constant("HIGH_VALUE"))
                .otherwise()
                    .setHeader("orderType", constant("STANDARD"))
            .end()
            // EIP: Idempotent Consumer - a reference the customer already used is answered with 409
            .process(exchange -> exchange.getMessage().setHeader("idempotencyKey",
                idempotencyKey(exchange.getMessage().getBody(Order.class))))
            .idempotentConsumer(header("idempotencyKey"), orderIdRepository)
                .skipDuplicate(false)
                .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(true))
                    .log(LoggingLevel.WARN, "Duplicate order reference ${body.externalReference}")
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(409))
                    .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
                    .setBody(simple("{\"status\":\"DUPLICATE\",\"orderReference\":\"${body.externalReference}\"}"))
                    .stop()
                .end()
            .end()
            // Component: Bean — prepare + save order + write OutboxEvent (atomically in one transaction)
            .bean(OrderService.class, "prepare")
            .log("Order ${body.orderId} stored: ${body.items.size} item(s), total ${body.currency} ${body.total} (GST ${body.taxTotal}), type ${body.orderType}")
            .setBody(exchange -> accepted(exchange))
            .marshal().json()
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:getOrder")
            .routeId("get-order")
            // Someone else's order answers as missing
            .bean(OrderAccessService.class, "findVisible(${header.id})")
            .choice()
                .when(body().isNull())
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setBody(simple("{\"status\":\"NOT_FOUND\",\"orderId\":\"${header.id}\"}"))
                .otherwise()
                    // A customer does not see fraud scores or our carrier cost
                    .bean(OrderViews.class, "forCurrentViewer")
                    .marshal().json()
            .end()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:updateOrderStatus")
            .routeId("update-order-status")
            .convertBodyTo(String.class)
            .setProperty("rule", constant("Request body is required"))
            .validate().simple("${body} != null && ${body} != ''")
            .setHeader("newStatus").jsonpath("$.status", true)
            // Predicate: header().in() - only these two values are accepted
            .setProperty("rule", constant("status must be CONFIRMED or CANCELLED"))
            .validate(header("newStatus").in("CONFIRMED", "CANCELLED"))
            .bean(OrderAccessService.class, "requireOwnerOrAdmin(${header.id})")
            .bean(OrderService.class, "updateStatus")
            .choice()
                .when(body().isNull())
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setBody(simple("{\"status\":\"NOT_FOUND\",\"orderId\":\"${header.id}\"}"))
                .otherwise()
                    .log("Order ${body.orderId} is now ${body.status}")
                    .bean(OrderViews.class, "forCurrentViewer")
                    .marshal().json()
            .end()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:deleteOrder")
            .routeId("delete-order")
            .bean(OrderService.class, "delete")
            .choice()
                .when(body().isEqualTo(false))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setBody(simple("{\"status\":\"NOT_FOUND\",\"orderId\":\"${header.id}\"}"))
                .otherwise()
                    .log("Order ${header.id} deleted")
                    .setBody(simple("{\"status\":\"DELETED\",\"orderId\":\"${header.id}\"}"))
            .end()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:listOrders")
            .routeId("list-orders")
            // Admins see every order, customers their own
            .setHeader("viewerCustomerId", method(OrderAccessService.class, "customerFilter"))
            // Component: SQL - a plain query, newest order first
            .choice()
                .when(header("viewerCustomerId").isNull())
                    .to("sql:select order_id, customer_id, status, order_type, currency, total, tax_total, created_at from orders "
                        + "where deleted_at is null order by created_at desc")
                .otherwise()
                    .to("sql:select order_id, customer_id, status, order_type, currency, total, tax_total, created_at from orders "
                        + "where deleted_at is null and customer_id = :#viewerCustomerId order by created_at desc")
            .end()
            .marshal().json()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        // Phase 1.1: DLQ Operations Routes
        from("direct:listDlq")
            .routeId("list-dlq")
            .bean(DeadLetterService.class, "findAll")
            .marshal().json()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        // Closes an entry without a replay: {"note": "why"}
        from("direct:dismissDlq")
            .routeId("dismiss-dlq")
            .convertBodyTo(String.class)
            .setHeader("dismissNote").jsonpath("$.note", true)
            .process(OrderRoute::dismiss)
            .marshal().json()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        from("direct:replayDlq")
            .routeId("replay-dlq")
            .bean(DeadLetterService.class, "replay(${header.id})")
            .choice()
                .when(body().isNull())
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(404))
                    .setBody(simple("{\"status\":\"NOT_FOUND\",\"message\":\"DLQ event not found\"}"))
                .otherwise()
                    .marshal().json()
            .end()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));
    }

    private static void dismiss(Exchange exchange) {
        String note = exchange.getMessage().getHeader("dismissNote", String.class);
        Long id = Long.valueOf(exchange.getMessage().getHeader("id", String.class));
        exchange.getMessage().setBody(exchange.getContext().getRegistry()
                .lookupByNameAndType("deadLetterService", DeadLetterService.class).dismiss(id, note));
    }

    private static void toNewOrder(Exchange exchange) {
        Order order = exchange.getMessage().getBody(OrderRequest.class).toOrder(Order.newId());
        exchange.getMessage().setBody(order);
        exchange.getMessage().setHeader("orderId", order.getOrderId());
    }

    /**
     * A reference is unique per customer, so two customers may both use "PO-1".
     * Orders without one cannot be duplicates: their id is new.
     */
    private static String idempotencyKey(Order order) {
        return order.getExternalReference() == null
                ? order.getOrderId()
                : "REF:" + order.getCustomerId() + ":" + order.getExternalReference();
    }

    private static Map<String, Object> accepted(Exchange exchange) {
        Order order = exchange.getMessage().getBody(Order.class);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("status", order.getStatus());
        reply.put("orderId", order.getOrderId());
        reply.put("orderReference", order.getExternalReference());
        reply.put("orderType", order.getOrderType());
        reply.put("currency", order.getCurrency());
        reply.put("subtotal", order.getSubtotal());
        reply.put("taxTotal", order.getTaxTotal());
        reply.put("total", order.getTotal());
        return reply;
    }
}
