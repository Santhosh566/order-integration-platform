package com.example.orders;

import java.math.BigDecimal;
import com.example.orders.model.DeadLetterEvent;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.OutboxEvent;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.DeadLetterEventRepository;
import com.example.orders.repository.OutboxEventRepository;
import com.example.orders.service.StuckOrderRecoveryService;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orders left half done, as after a crash mid-saga, are finished or unwound.
 * Each test makes the stuck state directly in the database and then runs one recovery pass.
 */
@TestPropertySource(properties = {
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200",
        // This class runs the recovery passes itself
        "orders.recovery.poll-ms=3600000"
})
class StuckOrderRecoveryIT extends IntegrationTestBase {

    @Autowired StuckOrderRecoveryService recovery;
    @Autowired ProducerTemplate producerTemplate;
    @Autowired DeadLetterEventRepository deadLetterEventRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CamelContext camelContext;

    @Test
    void reservedButNeverPaid_returnsTheStock() {
        String sku = stock(8, 2);
        String orderId = stuckOrder(OrderStatus.INVENTORY_RESERVED, sku, 2, null);

        runRecovery();

        awaitStatus(orderId, OrderStatus.PAYMENT_FAILED);
        assertStock(sku, 10, 0);
    }

    @Test
    void paidButNotShipped_isShipped() {
        String sku = stock(8, 2);
        String orderId = stuckOrder(OrderStatus.CONFIRMED, sku, 2, "TXN-STRIPE-STUCK");

        runRecovery();

        awaitStatus(orderId, OrderStatus.DELIVERED);
        assertStock(sku, 8, 0);
        assertThat(order(orderId).getRefundedAt()).isNull();
    }

    @Test
    void legacyPaidOrderWithNoAddress_isRefunded_notShipped() {
        // Like orders from before addresses and payment ids were recorded
        String sku = stock(8, 2);
        String orderId = stuckOrder(OrderStatus.CONFIRMED, sku, 2, null);
        clearAddress(orderId);

        runRecovery();

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        assertStock(sku, 10, 0);
        assertThat(order(orderId).getRefundedAt()).isNotNull();
    }

    @Test
    void paidOrderThatCannotBeShipped_isRefunded() {
        String sku = stock(8, 2);
        String orderId = stuckOrder(OrderStatus.CONFIRMED, sku, 2, "TXN-STRIPE-STUCK");
        setAttempts(orderId, 3);

        runRecovery();

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        assertStock(sku, 10, 0);
        assertThat(order(orderId).getRefundedAt()).isNotNull();
    }

    @Test
    void pendingWhoseMessageWasLost_isProcessedAgain() {
        String sku = stock(5, 0);
        String orderId = stuckOrder(OrderStatus.PENDING, sku, 1, null);
        // The lost message already counts as processed
        jdbcTemplate.update("insert into CAMEL_MESSAGEPROCESSED (processorName, messageId, createdAt) values (?, ?, now())",
                "kafka-order-consumer", orderId);

        runRecovery();

        awaitStatus(orderId, OrderStatus.DELIVERED);
        assertStock(sku, 4, 0);
    }

    @Test
    void pendingThatNeverGetsThrough_goesToTheDlq_andCanBeReplayed() {
        String sku = stock(5, 0);
        String orderId = stuckOrder(OrderStatus.PENDING, sku, 1, null);
        setAttempts(orderId, 3);

        runRecovery();

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        DeadLetterEvent dead = Awaitility.await("dead letter for " + orderId).atMost(Duration.ofSeconds(10))
                .until(() -> deadLetterEventRepository.findAll().stream()
                        .filter(e -> orderId.equals(e.getOrderId())).findFirst().orElse(null), e -> e != null);

        given().post("/api/dlq/" + dead.getId() + "/replay").then().statusCode(200);

        awaitStatus(orderId, OrderStatus.DELIVERED);
    }

    @Test
    void legacyPendingOrderWithNoAddress_isClosedWithoutACharge() {
        String sku = stock(5, 0);
        String orderId = stuckOrder(OrderStatus.PENDING, sku, 1, null);
        clearAddress(orderId);

        runRecovery();

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        assertStock(sku, 5, 0);
        assertThat(order(orderId).getPaymentTransactionId()).isNull();
    }

    @Test
    void ordersStillMoving_areLeftAlone() throws Exception {
        String recent = stuckOrder(OrderStatus.PENDING, stock(5, 0), 1, null);
        jdbcTemplate.update("update orders set status_changed_at = now() where order_id = ?", recent);

        camelContext.getRouteController().stopRoute("outbox-poller");
        String waitingForKafka = stuckOrder(OrderStatus.PENDING, stock(5, 0), 1, null);
        try {
            outboxEventRepository.save(OutboxEvent.from(order(waitingForKafka)));

            recovery.recover();

            assertThat(order(recent).getRecoveryAttempts()).isZero();
            assertThat(order(waitingForKafka).getRecoveryAttempts()).isZero();
        } finally {
            for (String orderId : List.of(recent, waitingForKafka)) {
                jdbcTemplate.update("delete from outbox_events where order_id = ?", orderId);
                jdbcTemplate.update("delete from order_items where order_id = ?", orderId);
                jdbcTemplate.update("delete from orders where order_id = ?", orderId);
            }
            camelContext.getRouteController().startRoute("outbox-poller");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** One recovery pass, as the timer route runs it. */
    private void runRecovery() {
        recovery.recover().forEach(orderId -> producerTemplate.sendBody("direct:resumePaidOrder", orderId));
    }

    private String stock(int available, int reserved) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        inventoryRepository.save(new InventoryItem(sku, available, reserved));
        inDefaultWarehouse(sku, available, reserved);
        return sku;
    }

    private String stuckOrder(OrderStatus status, String sku, int quantity, String paymentTransactionId) {
        LocalDateTime anHourAgo = LocalDateTime.now().minusHours(1);
        Order order = new Order();
        order.setOrderId(Order.newId());
        order.setCustomerId("CUST-100");
        order.setItems(new ArrayList<>(List.of(new OrderItem(sku, quantity, new BigDecimal("25.0")))));
        order.recalculateTotals();
        order.setStatus(status);
        order.setCreatedAt(anHourAgo);
        order.setStatusChangedAt(anHourAgo);
        order.setShippingAddress(address());
        if (paymentTransactionId != null) {
            order.setPaymentTransactionId(paymentTransactionId);
            order.setPaymentGateway("STRIPE");
        }
        return orderRepository.save(order).getOrderId();
    }

    private static ShippingAddress address() {
        ShippingAddress address = new ShippingAddress();
        address.setRecipientName("Alice Smith");
        address.setRecipientPhone("+91-98450-00101");
        address.setLine1("12, 4th Cross, Indiranagar");
        address.setCity("Bengaluru");
        address.setState("Karnataka");
        address.setPostalCode("560038");
        address.setCountry("IN");
        return address;
    }

    private void clearAddress(String orderId) {
        jdbcTemplate.update("update orders set address_line1 = null, recipient_name = null, recipient_phone = null, "
                + "city = null, state_region = null, postal_code = null, country = null where order_id = ?", orderId);
    }

    private void setAttempts(String orderId, int attempts) {
        jdbcTemplate.update("update orders set recovery_attempts = ? where order_id = ?", attempts, orderId);
    }
}
