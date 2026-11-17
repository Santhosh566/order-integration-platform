package com.example.orders;

import java.math.BigDecimal;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.ShippingAddress;
import com.example.orders.model.ShippingQuote;
import com.example.orders.service.CarrierService;
import com.example.orders.service.StuckOrderRecoveryService;
import io.restassured.RestAssured;
import org.apache.camel.Exchange;
import org.apache.camel.saga.CamelSagaService;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.service.lra.LRASagaService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * Sagas kept in a real Narayana LRA coordinator, which calls the app back over HTTP to compensate.
 *
 * The coordinator reaches the app through the Docker host gateway, as it does in compose.yaml,
 * so its calls arrive from a Docker network address. The test itself calls from 127.0.0.1,
 * which this class leaves out of orders.saga.lra-callers.
 */
@TestPropertySource(properties = {
        "camel.lra.enabled=true",
        // Longer than a failing step's retries (1 + 2 + 4 s), so only a stalled saga times out
        "orders.saga.timeout-ms=15000",
        "orders.saga.lra-callers=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16",
        // This class runs the recovery passes itself
        "orders.recovery.poll-ms=3600000"
})
class LraSagaIT extends IntegrationTestBase {

    static final int APP_PORT = freePort();

    static final GenericContainer<?> COORDINATOR =
            new GenericContainer<>("quay.io/jbosstm/lra-coordinator:7.2.2.Final-3.29.3")
                    .withExposedPorts(8080)
                    .withExtraHost("host.docker.internal", "host-gateway")
                    .waitingFor(Wait.forHttp("/lra-coordinator").forStatusCode(200));

    static {
        COORDINATOR.start();
    }

    @DynamicPropertySource
    static void coordinatorProperties(DynamicPropertyRegistry registry) {
        // A fixed port, because the coordinator must be told where to call before the app starts
        registry.add("server.port", () -> APP_PORT);
        registry.add("camel.lra.coordinator-url", LraSagaIT::coordinatorUrl);
        registry.add("camel.lra.local-participant-url", () -> "http://host.docker.internal:" + APP_PORT);
    }

    @MockitoSpyBean CarrierService carrierService;
    @Autowired CamelSagaService sagaService;
    @Autowired ProducerTemplate producerTemplate;
    @Autowired StuckOrderRecoveryService recovery;

    @Test
    void sagasRunThroughTheCoordinator_andASuccessfulOneIsClosed() {
        assertThat(sagaService).isInstanceOf(LRASagaService.class);
        String sku = newSku(10);

        String orderId = placeOrder(sku, 1, 10.0);

        awaitStatus(orderId, OrderStatus.DELIVERED);
        String sagaId = order(orderId).getSagaId();
        assertThat(sagaId).as("the reservation records its saga").isNotBlank();
        Awaitility.await("the coordinator to close saga " + sagaId).atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(activeSagas()).doesNotContain(sagaId));
    }

    @Test
    void aFailedStep_isCompensatedByTheCoordinatorCallingBack() {
        String sku = newSku(10);
        doThrow(new IllegalStateException("no carrier")).when(carrierService).bookWinningCarrier(forSku(sku), any(ShippingQuote.class));

        String orderId = placeOrder(sku, 2, 50.0);

        awaitStatus(orderId, OrderStatus.FULFILLMENT_FAILED);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(order(orderId).getRefundedAt()).as("refund issued").isNotNull());
        assertStock(sku, 10, 0);
    }

    @Test
    void aSagaStillOpenAtItsTimeout_isUndone_asAfterItsPodStopped() throws Exception {
        String sku = newSku(10);
        CountDownLatch podBack = new CountDownLatch(1);
        // The saga stalls after the charge, as if its pod had stopped there
        doAnswer(call -> {
            podBack.await(60, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(carrierService).bookWinningCarrier(forSku(sku), any(ShippingQuote.class));

        String orderId = placeOrder(sku, 2, 50.0);
        awaitStatus(orderId, OrderStatus.CONFIRMED);

        Awaitility.await("the coordinator to time the saga out").atMost(Duration.ofSeconds(40))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.FULFILLMENT_FAILED));
        assertThat(order(orderId).getRefundedAt()).as("the customer is refunded").isNotNull();
        assertStock(sku, 10, 0);

        // The stalled step carries on, and must not ship an order that was already undone
        podBack.countDown();
        Thread.sleep(2000);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.FULFILLMENT_FAILED);
        assertThat(order(orderId).getCarrier()).isNull();
    }

    @Test
    void aCompensationForAnotherSaga_changesNothing() {
        String sku = stock(8, 2);
        String orderId = orderIn(OrderStatus.INVENTORY_RESERVED, sku, null, "http://coordinator/lra-coordinator/the-real-saga");

        compensate("direct:compensateInventory", orderId, "http://coordinator/lra-coordinator/some-other-saga");

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertStock(sku, 8, 2);

        // The same call from the saga that holds the order undoes it; the host part does not matter
        compensate("direct:compensateInventory", orderId, "http://localhost:8087/lra-coordinator/the-real-saga");

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertStock(sku, 10, 0);
    }

    @Test
    void onceRecoveryTakesAnOrderOver_aLateCompensationIsIgnored() {
        String sku = stock(8, 2);
        String sagaId = "http://coordinator/lra-coordinator/" + UUID.randomUUID();
        String orderId = orderIn(OrderStatus.CONFIRMED, sku, "TXN-PAID-1", sagaId);
        backdate(orderId);

        // The pod stopped mid-saga and the coordinator was down: recovery ships the paid order
        recovery.recover().forEach(id -> producerTemplate.sendBody("direct:resumePaidOrder", id));
        assertThat(order(orderId).getSagaId()).isNull();
        awaitStatus(orderId, OrderStatus.DELIVERED);

        // The coordinator comes back and cancels the old saga
        compensate("direct:compensatePayment", orderId, sagaId);
        compensate("direct:compensateInventory", orderId, sagaId);

        Order order = order(orderId);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getRefundedAt()).as("a delivered order is never refunded").isNull();
    }

    @Test
    void theCallbackEndpoint_onlyAnswersTheCoordinatorsNetwork() {
        // The test calls from 127.0.0.1, which this class does not list
        RestAssured.given()
            .header(Exchange.SAGA_LONG_RUNNING_ACTION, "http://coordinator/lra-coordinator/x")
            .queryParam("Camel-Saga-Compensate", "direct://compensatePayment")
            .queryParam("orderId", "ORD-ANY")
            .put("/lra-participant/compensate")
            .then().statusCode(401);
    }

    private void compensate(String route, String orderId, String sagaId) {
        producerTemplate.sendBodyAndHeaders(route, null,
                Map.of("orderId", orderId, Exchange.SAGA_LONG_RUNNING_ACTION, sagaId));
    }

    private String activeSagas() {
        return RestAssured.given().baseUri("http://" + COORDINATOR.getHost()).port(COORDINATOR.getMappedPort(8080))
                .queryParam("Status", "Active")
                .get("/lra-coordinator").then().statusCode(200).extract().asString();
    }

    private static String coordinatorUrl() {
        return "http://" + COORDINATOR.getHost() + ":" + COORDINATOR.getMappedPort(8080);
    }

    private String stock(int available, int reserved) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        inventoryRepository.save(new InventoryItem(sku, available, reserved));
        return sku;
    }

    /** An order the pipeline left in the given status, held by the given saga. */
    private String orderIn(OrderStatus status, String sku, String paymentTransactionId, String sagaId) {
        Order order = new Order();
        order.setOrderId(Order.newId());
        order.setCustomerId("CUST-100");
        order.setItems(new ArrayList<>(List.of(new OrderItem(sku, 2, new BigDecimal("25.0")))));
        order.recalculateTotals();
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.now());
        order.setShippingAddress(address());
        order.setSagaId(sagaId);
        if (paymentTransactionId != null) {
            order.setPaymentTransactionId(paymentTransactionId);
            order.setPaymentGateway("STRIPE");
        }
        return orderRepository.save(order).getOrderId();
    }

    private void backdate(String orderId) {
        Order order = order(orderId);
        order.setStatusChangedAt(LocalDateTime.now().minusHours(1));
        orderRepository.save(order);
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

    private static Order forSku(String sku) {
        return ArgumentMatchers.argThat(o -> o != null && o.getItems().stream().anyMatch(i -> sku.equals(i.getSku())));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
