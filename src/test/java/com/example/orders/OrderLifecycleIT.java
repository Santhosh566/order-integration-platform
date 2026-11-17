package com.example.orders;

import com.example.orders.money.Money;
import java.math.BigDecimal;
import com.example.orders.model.DeadLetterEvent;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.OutboxEvent;
import com.example.orders.model.PaymentTransaction;
import com.example.orders.model.ShippingQuote;
import com.example.orders.repository.DeadLetterEventRepository;
import com.example.orders.repository.OutboxEventRepository;
import com.example.orders.repository.PaymentTransactionRepository;
import com.example.orders.service.CarrierService;
import com.example.orders.service.InventoryService;
import com.example.orders.service.OrderEventBroadcaster;
import com.example.orders.service.OutboxService;
import com.example.orders.service.PaymentGatewayService;
import com.example.orders.service.StuckOrderRecoveryService;
import io.restassured.http.ContentType;
import org.apache.camel.CamelContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Business rules of the order life cycle, with the warehouse signals in the test's hands:
 * auto-release is off, so an order waits at SHIPPED until the test sends them.
 */
@TestPropertySource(properties = {
        "orders.fulfillment.auto-release=false",
        "orders.fulfillment.signal-timeout-ms=4000",
        "orders.tracking.poll-ms=200"
})
class OrderLifecycleIT extends IntegrationTestBase {

    @MockitoSpyBean PaymentGatewayService paymentGateway;
    @MockitoSpyBean CarrierService carrierService;
    @MockitoSpyBean OrderEventBroadcaster broadcaster;

    @Autowired InventoryService inventoryService;
    @Autowired OutboxService outboxService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired DeadLetterEventRepository deadLetterEventRepository;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired StuckOrderRecoveryService stuckOrderRecovery;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CamelContext camelContext;
    @Autowired ApplicationContext applicationContext;

    // ── Saga rollback ──────────────────────────────────────────────────────────

    @Test
    void bothGatewaysDown_releasesStock_andQuarantinesTheOriginalMessage() {
        String sku = newSku(10);
        doThrow(new IllegalStateException("stripe down")).when(paymentGateway).chargeStripe(forSku(sku));
        doThrow(new IllegalStateException("adyen down")).when(paymentGateway).chargeAdyen(forSku(sku));

        String orderId = placeOrder(sku, 3, 20.0);

        awaitStatus(orderId, OrderStatus.PAYMENT_FAILED);
        assertStock(sku, 10, 0);
        assertThat(order(orderId).getRefundedAt()).as("nothing was charged, so nothing is refunded").isNull();

        DeadLetterEvent dead = awaitDeadLetter(orderId);
        assertThat(dead.getPayload()).as("replayable JSON, not a Java toString").startsWith("{").contains(sku);
    }

    @Test
    void carrierFailure_afterPayment_refundsAndReleasesStock() {
        String sku = newSku(10);
        doThrow(new IllegalStateException("no carrier")).when(carrierService).bookWinningCarrier(forSku(sku), any(ShippingQuote.class));

        String orderId = placeOrder(sku, 2, 50.0);

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        Order order = order(orderId);
        assertThat(order.getPaymentTransactionId()).isNotNull();
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(order(orderId).getRefundedAt()).as("refund issued").isNotNull());
        assertStock(sku, 10, 0);
    }

    @Test
    void replayFromDeadLetterQueue_runsTheFailedOrderAgain() {
        String sku = newSku(10);
        doThrow(new IllegalStateException("stripe down")).when(paymentGateway).chargeStripe(forSku(sku));
        doThrow(new IllegalStateException("adyen down")).when(paymentGateway).chargeAdyen(forSku(sku));
        String orderId = placeOrder(sku, 1, 20.0);
        awaitStatus(orderId, OrderStatus.PAYMENT_FAILED);
        DeadLetterEvent dead = awaitDeadLetter(orderId);

        org.mockito.Mockito.reset(paymentGateway);
        given().post("/api/dlq/" + dead.getId() + "/replay").then().statusCode(200);

        awaitStatus(orderId, OrderStatus.SHIPPED);
        assertStock(sku, 9, 1);
        given().post("/api/dlq/" + dead.getId() + "/replay").then().statusCode(409);
    }

    @Test
    void replay_keepsTheEarlierChargeAndRefund_inThePaymentHistory() {
        String sku = newSku(10);
        doThrow(new IllegalStateException("no carrier")).when(carrierService).bookWinningCarrier(forSku(sku), any(ShippingQuote.class));
        String orderId = placeOrder(sku, 1, 20.0);
        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(order(orderId).getRefundedAt()).isNotNull());
        DeadLetterEvent dead = awaitDeadLetter(orderId);

        org.mockito.Mockito.reset(carrierService);
        given().post("/api/dlq/" + dead.getId() + "/replay").then().statusCode(200);
        awaitStatus(orderId, OrderStatus.SHIPPED);

        assertThat(paymentTransactionRepository.findByOrderIdOrderByIdAsc(orderId))
                .extracting(PaymentTransaction::getKind)
                .containsExactly(PaymentTransaction.Kind.CHARGE, PaymentTransaction.Kind.REFUND, PaymentTransaction.Kind.CHARGE);
    }

    // ── Cancellation ───────────────────────────────────────────────────────────

    @Test
    void orderCancelledBeforeKafkaDelivery_isNeverReservedOrCharged() throws Exception {
        String sku = newSku(10);
        String orderId = placeOrder(sku, 2, 20.0);

        cancel(orderId).statusCode(200).body("message", equalTo("Cancelled before payment"));
        awaitOutboxPublished(orderId);
        Thread.sleep(2000);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertStock(sku, 10, 0);
        verify(paymentGateway, never()).chargeStripe(forSku(sku));
    }

    @Test
    void cancellingABookedOrder_refundsRestoresStock_andTrackingLeavesItAlone() throws Exception {
        String sku = newSku(10);
        String orderId = shippedOrder(sku, 2);

        cancel(orderId).statusCode(200).body("message", equalTo("Stock restored and refund issued"));

        assertStock(sku, 10, 0);
        assertThat(order(orderId).getRefundedAt()).isNotNull();
        Thread.sleep(1000);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingARejectedOrder_createsNoStock() {
        String sku = newSku(1);
        String orderId = placeOrder(sku, 5, 20.0);
        awaitStatus(orderId, OrderStatus.OUT_OF_STOCK);

        cancel(orderId).statusCode(409);

        assertStock(sku, 1, 0);
    }

    @Test
    void cancellingAnUnknownOrder_is404() {
        cancel("ORD-DOES-NOT-EXIST").statusCode(404);
    }

    @Test
    void anOrderOnTheRoadCannotBeCancelled_andEndsDelivered() {
        String sku = newSku(10);
        String orderId = shippedOrder(sku, 2);
        sendBothSignals(orderId);
        awaitStatus(orderId, OrderStatus.DELIVERED);

        cancel(orderId).statusCode(409);

        assertStock(sku, 8, 0);
    }

    @Test
    void statusEndpoint_cannotConfirmWithoutPayment_andCancelsThroughTheWorkflow() {
        String sku = newSku(10);
        String orderId = placeOrder(sku, 1, 20.0);

        patchStatus(orderId, "CONFIRMED").statusCode(409);
        patchStatus(orderId, "CANCELLED").statusCode(200).body("status", equalTo("CANCELLED"));
    }

    // ── Stock ──────────────────────────────────────────────────────────────────

    @Test
    void deliveredOrder_usesUpItsReservedUnits() {
        String sku = newSku(10);
        String orderId = shippedOrder(sku, 4);
        assertStock(sku, 6, 4);

        sendBothSignals(orderId);
        awaitStatus(orderId, OrderStatus.DELIVERED);

        assertStock(sku, 6, 0);
    }

    @Test
    void concurrentOrders_cannotOversell() throws Exception {
        String sku = newSku(3);
        List<Callable<Boolean>> reservations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Order order = new Order();
            order.setOrderId("ORD-RACE-" + UUID.randomUUID());
            order.setCustomerId("CUST-100");
            order.setStatus(OrderStatus.PENDING);
            order.setItems(new ArrayList<>(List.of(new OrderItem(sku, 1, new BigDecimal("10.0")))));
            orderRepository.save(order);
            reservations.add(() -> inventoryService.reserve(order.getOrderId(), UUID.randomUUID().toString()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        int reserved = 0;
        for (Future<Boolean> result : pool.invokeAll(reservations)) {
            reserved += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(reserved).isEqualTo(3);
        assertStock(sku, 0, 3);
    }

    @Test
    void repeatedLinesForOneSku_areAddedUp() {
        String sku = newSku(3);
        String orderId = submitOrder(Map.of("customerId", "CUST-100", "items", List.of(
                Map.of("sku", sku, "quantity", 2, "price", 10.0),
                Map.of("sku", sku, "quantity", 2, "price", 10.0))))
            .statusCode(202).extract().path("orderId");

        awaitStatus(orderId, OrderStatus.OUT_OF_STOCK);
        assertStock(sku, 3, 0);
    }

    // ── Fulfillment signals ────────────────────────────────────────────────────

    @Test
    void oneSignal_orTheSameSignalTwice_doesNotReleaseTheOrder() throws Exception {
        String orderId = shippedOrder(newSku(10), 1);

        sendSignal("payment-captured", orderId).statusCode(202);
        sendSignal("payment-captured", orderId).statusCode(202);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from fulfillment_aggregation where id = ?", Integer.class, orderId))
            .as("the waiting signal is stored in PostgreSQL").isEqualTo(1);

        // Well past the 4 second completion timeout
        Thread.sleep(7000);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from fulfillment_aggregation where id = ?", Integer.class, orderId))
            .as("the expired group is discarded").isZero();
    }

    @Test
    void bothSignals_releaseTheOrderOnce() {
        String sku = newSku(10);
        String orderId = shippedOrder(sku, 1);

        sendBothSignals(orderId);

        awaitStatus(orderId, OrderStatus.DELIVERED);
        sendSignal("payment-captured", orderId).statusCode(409);
        assertThat(order(orderId).getStatus()).as("a late signal cannot move it backwards").isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void signalForAnUnknownOrder_is404() {
        sendSignal("warehouse-packed", "ORD-DOES-NOT-EXIST").statusCode(404);
    }

    // ── Validation and ids ─────────────────────────────────────────────────────

    @Test
    void anyBadLine_rejectsTheOrder() {
        String sku = newSku(10);

        submitOrder(Map.of("customerId", "CUST-100", "items", List.of(
                Map.of("sku", sku, "quantity", 1, "price", 100.0),
                Map.of("sku", sku, "quantity", -5, "price", 100.0))))
            .statusCode(400).body("reason", containsString("quantity"));

        submitOrder(Map.of("customerId", "CUST-100", "items", List.of(
                Map.of("sku", "NO-SUCH-PRODUCT", "quantity", 1))))
            .statusCode(400).body("reason", containsString("Unknown product"));

        assertStock(sku, 10, 0);
    }

    @Test
    void theCatalogueSetsThePrice_notTheCaller() {
        String sku = newSku(10);
        setPrice(sku, 45.00);

        String orderId = submitOrder(Map.of("customerId", "CUST-100", "items", List.of(
                Map.of("sku", sku, "quantity", 2, "price", 0.01))))
            .statusCode(202).body("total", equalTo(90.0f)).extract().path("orderId");

        awaitStatus(orderId, OrderStatus.SHIPPED);
        assertThat(order(orderId).getTotal()).isEqualByComparingTo("90.00");
        assertThat(order(orderId).getItems()).allSatisfy(item -> assertThat(item.getPrice()).isEqualByComparingTo("45.00"));
    }

    @Test
    void orderStuckAtShipped_pastTheDeadline_isRefundedAndRestocked() {
        String sku = newSku(10);
        String orderId = shippedOrder(sku, 2);
        jdbcTemplate.update("update orders set status_changed_at = now() - interval '2 days' where order_id = ?", orderId);

        stuckOrderRecovery.recover();

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        assertStock(sku, 10, 0);
        assertThat(order(orderId).getRefundedAt()).isNotNull();
        // The warehouse is too late now
        sendSignal("warehouse-packed", orderId).statusCode(409);
    }

    @Test
    void csvWithOneBadLine_importsNothing() {
        String sku = newSku(10);
        String good = "ORD-CSV-" + UUID.randomUUID();
        String csv = "orderId,customerId,sku,quantity,price\n"
                + good + ",CUST-100," + sku + ",1,10.00\n"
                + "ORD-CSV-BAD,CUST-100," + sku + ",-2,10.00\n";

        given().contentType("text/csv").body(csv).post("/api/orders/csv").then().statusCode(400);

        assertThat(orderRepository.findByCustomerIdAndExternalReference("CUST-100", good)).isEmpty();
    }

    @Test
    void csvOrders_getServerIds_andKeepThePartnerNumber() {
        String partnerNumber = "ORD-CSV-" + UUID.randomUUID();
        String csv = "orderId,customerId,sku,quantity,price\n" + partnerNumber + ",CUST-100," + newSku(10) + ",1,10.00\n";

        given().contentType("text/csv").body(csv).post("/api/orders/csv").then().statusCode(202);

        Order order = orderRepository.findByCustomerIdAndExternalReference("CUST-100", partnerNumber).orElseThrow();
        assertThat(order.getOrderId()).startsWith("ORD-").isNotEqualTo(partnerNumber);
    }

    @Test
    void callerOrderId_isOnlyAReference_uniquePerCustomer() {
        String sku = newSku(10);
        String reference = "PO-" + UUID.randomUUID();
        Map<String, Object> fromAlice = Map.of("orderId", reference, "customerId", "CUST-100",
                "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0)));
        Map<String, Object> fromBob = Map.of("orderId", reference, "customerId", "CUST-101",
                "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0)));

        String orderId = submitOrder(fromAlice).statusCode(202)
                .body("orderReference", equalTo(reference)).extract().path("orderId");

        assertThat(orderId).startsWith("ORD-").isNotEqualTo(reference);
        submitOrder(fromAlice).statusCode(409);
        submitOrder(fromBob).statusCode(202);
    }

    @Test
    void callerCannotSetPaymentInvoiceOrFraudFields() {
        String sku = newSku(10);
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("customerId", "CUST-100");
        request.put("items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0)));
        request.put("status", "DELIVERED");
        request.put("paymentTransactionId", "FAKE-TXN");
        request.put("refundedAt", "2026-01-01T00:00:00");
        request.put("invoiceNumber", "INV-FAKE");
        request.put("carrier", "FAKE");
        request.put("fraudStatus", "FAKE");

        String orderId = submitOrder(request).statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.SHIPPED);

        Order order = order(orderId);
        assertThat(order.getPaymentTransactionId()).startsWith("TXN-");
        assertThat(order.getRefundedAt()).isNull();
        assertThat(order.getInvoiceNumber()).isNotEqualTo("INV-FAKE");
        assertThat(order.getCarrier()).isNotEqualTo("FAKE");
        assertThat(order.getFraudStatus()).isNotEqualTo("FAKE");
    }

    @Test
    void validatedEndpoint_rejectsFieldsACallerMayNotSet() {
        given().contentType(ContentType.JSON)
            .body(Map.of("customerId", "CUST-100", "paymentTransactionId", "FAKE-TXN",
                    "items", List.of(Map.of("sku", newSku(10), "quantity", 1, "price", 10.0))))
            .post("/api/orders/validated")
            .then().statusCode(400);
    }

    @Test
    void blacklistedCustomer_isRejected_evenForASmallOrder() {
        String sku = newSku(10);
        String orderId = submitOrder(Map.of("customerId", "FRAUD-BLACK-LIST-01",
                "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 20.0))))
            .statusCode(202).extract().path("orderId");

        awaitStatus(orderId, OrderStatus.FRAUD_REJECTED);
        assertStock(sku, 10, 0);
        verify(paymentGateway, never()).chargeStripe(forSku(sku));
    }

    @Test
    void serverIssuesTheOrderId_andCallerIdsMustBeSafe() {
        String sku = newSku(10);
        String first = placeOrder(sku, 1, 10.0);
        String second = placeOrder(sku, 1, 10.0);
        assertThat(first).startsWith("ORD-").isNotEqualTo(second);

        submitOrder(Map.of("orderId", "../../evil", "customerId", "CUST-100",
                "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0))))
            .statusCode(400).body("reason", containsString("orderId"));

        submitOrder(Map.of("customerId", "<script>alert(1)</script>",
                "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0))))
            .statusCode(400);
    }

    @Test
    void validatedEndpoint_alsoIssuesTheId() {
        String sku = newSku(10);
        given().contentType(ContentType.JSON)
            .body(Map.of("customerId", "CUST-100", "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0))))
            .post("/api/orders/validated")
            .then().statusCode(202).body("orderId", startsWith("ORD-"));
    }

    // ── Live updates ───────────────────────────────────────────────────────────

    @Test
    void everyStatusChangeIsBroadcast_andTheHeartbeatIsScheduled() {
        Set<OrderStatus> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        org.mockito.Mockito.doAnswer(call -> {
            if (call.getArgument(1) instanceof Order o) {
                seen.add(o.getStatus());
            }
            return call.callRealMethod();
        }).when(broadcaster).broadcast(ArgumentMatchers.eq("ORDER_UPDATED"), any());

        String orderId = placeOrder(newSku(10), 1, 20.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        cancel(orderId).statusCode(200);

        assertThat(seen).contains(OrderStatus.PENDING, OrderStatus.INVENTORY_RESERVED,
                OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.CANCELLED);
        assertThat(applicationContext.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .isNotEmpty();
    }

    // ── Invoices ───────────────────────────────────────────────────────────────

    @Test
    void invoice_matchesTheCharge_andKeepsItsNumber() throws Exception {
        String orderId = shippedOrder(newSku(10), 2);
        BigDecimal total = order(orderId).getTotal();

        String text = invoiceText(orderId);
        String number = order(orderId).getInvoiceNumber();

        // GST numbering: series / financial year / serial, at most 16 characters
        assertThat(number).matches("INV/\\d{2}-\\d{2}/\\d{6}").hasSizeLessThanOrEqualTo(16);
        assertThat(text).contains("TAX INVOICE", number, Money.formatCode(total, "INR"), "Payment Status: PAID",
                "GSTIN", "Place of Supply");

        // A reprint keeps the number
        invoiceText(orderId);
        assertThat(order(orderId).getInvoiceNumber()).isEqualTo(number);
    }

    @Test
    void refundedOrder_showsRefundedOnItsInvoice() throws Exception {
        String orderId = shippedOrder(newSku(10), 1);
        cancel(orderId).statusCode(200);

        assertThat(invoiceText(orderId)).contains("Payment Status: REFUNDED").doesNotContain("Payment Status: PAID");
    }

    @Test
    void unpaidOrder_hasNoInvoice() {
        String orderId = placeOrder(newSku(0), 1, 10.0);
        awaitStatus(orderId, OrderStatus.OUT_OF_STOCK);

        given().get("/api/invoices/" + orderId).then().statusCode(409);
        given().get("/api/invoices/ORD-DOES-NOT-EXIST").then().statusCode(404);
    }

    // ── Revenue ────────────────────────────────────────────────────────────────

    @Test
    void revenue_countsOnlyPaidOrders() {
        String sku = newSku(10);
        BigDecimal before = totalRevenue();

        String rejected = placeOrder(newSku(0), 1, 500.0);
        awaitStatus(rejected, OrderStatus.OUT_OF_STOCK);
        String cancelled = shippedOrder(sku, 1);
        cancel(cancelled).statusCode(200);
        String paid = shippedOrder(sku, 2);

        assertThat(totalRevenue()).isEqualByComparingTo(before.add(order(paid).getTotal()));
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    @Test
    void paidOrdersCannotBeDeleted() {
        String orderId = shippedOrder(newSku(10), 1);

        given().delete("/api/orders/" + orderId).then().statusCode(409);
    }

    @Test
    void delete_hidesTheOrderButKeepsTheRecord_andTheReferenceStaysUsed() {
        String sku = newSku(0);
        Map<String, Object> request = Map.of("orderId", "PO-KEEP-" + UUID.randomUUID(), "customerId", "CUST-100",
                "items", List.of(Map.of("sku", sku, "quantity", 1, "price", 10.0)));
        String orderId = submitOrder(request).statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.OUT_OF_STOCK);

        given().delete("/api/orders/" + orderId).then().statusCode(200);

        given().get("/api/orders/" + orderId).then().statusCode(404);
        assertThat(given().get("/api/orders").asString()).doesNotContain(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from orders where order_id = ?", Integer.class, orderId)).isEqualTo(1);
        submitOrder(request).statusCode(409);
    }

    // ── Outbox ─────────────────────────────────────────────────────────────────

    @Test
    void twoPodsClaimingTheOutbox_neverGetTheSameRow() throws Exception {
        camelContext.getRouteController().stopRoute("outbox-poller");
        try {
            for (int i = 0; i < 40; i++) {
                Order order = new Order();
                order.setOrderId("ORD-OUTBOX-" + UUID.randomUUID());
                outboxEventRepository.save(OutboxEvent.from(order));
            }

            ExecutorService pods = Executors.newFixedThreadPool(2);
            Callable<List<OutboxEvent>> claim = () -> outboxService.claimPendingEvents();
            List<OutboxEvent> first = pods.submit(claim).get();
            List<OutboxEvent> second = pods.submit(claim).get();
            pods.shutdown();

            Set<String> ids = new HashSet<>();
            first.forEach(e -> ids.add(e.getId()));
            second.forEach(e -> ids.add(e.getId()));
            assertThat(ids).hasSize(first.size() + second.size());
            assertThat(first).allSatisfy(e -> {
                assertThat(e.getStatus()).isEqualTo("PROCESSING");
                assertThat(e.getClaimedAt()).isBeforeOrEqualTo(LocalDateTime.now());
            });
        } finally {
            jdbcTemplate.update("delete from outbox_events where order_id like 'ORD-OUTBOX-%'");
            camelContext.getRouteController().startRoute("outbox-poller");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Order forSku(String sku) {
        return ArgumentMatchers.argThat(o -> o != null && o.getItems().stream().anyMatch(i -> sku.equals(i.getSku())));
    }

    /** Places an order and waits until payment and carrier booking are done. */
    private String shippedOrder(String sku, int quantity) {
        String orderId = placeOrder(sku, quantity, 25.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        return orderId;
    }

    private void sendBothSignals(String orderId) {
        sendSignal("payment-captured", orderId).statusCode(202);
        sendSignal("warehouse-packed", orderId).statusCode(202);
    }

    private io.restassured.response.ValidatableResponse sendSignal(String signal, String orderId) {
        return given().contentType(ContentType.JSON).body(Map.of("orderId", orderId))
                .post("/api/fulfillment/" + signal).then();
    }

    private io.restassured.response.ValidatableResponse patchStatus(String orderId, String status) {
        return given().contentType(ContentType.JSON).body(Map.of("status", status))
                .patch("/api/orders/" + orderId + "/status").then();
    }


    private BigDecimal totalRevenue() {
        // Read as BigDecimal: the default float would round a large total
        return given().get("/api/admin/metrics").then().statusCode(200)
                .extract().jsonPath(io.restassured.path.json.config.JsonPathConfig.jsonPathConfig()
                        .numberReturnType(io.restassured.path.json.config.JsonPathConfig.NumberReturnType.BIG_DECIMAL))
                .get("totalRevenue");
    }

    private DeadLetterEvent awaitDeadLetter(String orderId) {
        return Awaitility.await("dead letter for " + orderId).atMost(Duration.ofSeconds(30))
                .until(() -> deadLetterEventRepository.findAll().stream()
                        .filter(e -> orderId.equals(e.getOrderId())).findFirst().orElse(null),
                       e -> e != null);
    }

    private void awaitOutboxPublished(String orderId) {
        Awaitility.await("outbox publish of " + orderId).atMost(Duration.ofSeconds(30))
                .until(() -> outboxEventRepository.findByStatus("PUBLISHED").stream()
                        .anyMatch(e -> orderId.equals(e.getOrderId())));
    }
}
