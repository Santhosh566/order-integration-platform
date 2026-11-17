package com.example.orders;

import com.example.orders.model.CustomerNotification;
import com.example.orders.model.DeliveryAssignment;
import com.example.orders.model.DeliveryStatus;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.CustomerNotificationRepository;
import com.example.orders.repository.DeliveryAssignmentRepository;
import com.example.orders.service.DeliveryCodeGenerator;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.response.ValidatableResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doReturn;

/**
 * Delivery users take orders, pick them up and hand them over against the customer's code.
 * Nothing moves by itself here: the delivery timer is off, as in production.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class DeliveryIT extends IntegrationTestBase {

    private static final String CODE = "123456";

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @Autowired DeliveryAssignmentRepository assignments;
    @Autowired CustomerNotificationRepository notifications;

    @BeforeEach
    void fixedCode() {
        doReturn(CODE).when(codeGenerator).next();
    }

    @Test
    void driverDeliversAnOrder_withTheCustomersCode() throws Exception {
        String sku = newSku(10);
        String orderId = readyOrder(sku, 2);
        Thread.sleep(1000);
        assertThat(order(orderId).getStatus()).as("no timer delivers it").isEqualTo(OrderStatus.READY_FOR_DISPATCH);

        // The list shows the area only, never the street or phone
        String list = driverOne().get("/api/delivery/available").then().statusCode(200)
            .body("orderId", hasItem(orderId)).extract().asString();
        assertThat(list).doesNotContain("4th Cross").doesNotContain("+91-98450-00101");

        action(driverOne(), orderId, "take").statusCode(200).body("address.line1", equalTo("12, 4th Cross, Indiranagar"));
        action(driverTwo(), orderId, "take").statusCode(409);
        action(driverTwo(), orderId, "pickup").statusCode(404);

        action(driverOne(), orderId, "pickup").statusCode(200);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertStock(sku, 8, 0);

        action(driverOne(), orderId, "out-for-delivery").statusCode(200);
        // The code goes out by email, and is then removed from the database
        Awaitility.await("the delivery code to be sent").atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(notifications.findByOrderIdOrderByCreatedAtAsc(orderId))
                .filteredOn(CustomerNotification::isSecret).singleElement()
                .satisfies(sent -> {
                    assertThat(sent.getStatus()).isEqualTo(CustomerNotification.Status.SENT);
                    assertThat(sent.getText()).isNull();
                }));
        action(driverOne(), orderId, "deliver", Map.of("code", "000000")).statusCode(400);
        action(driverOne(), orderId, "deliver", Map.of("code", CODE)).statusCode(200);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(attempts(orderId)).extracting(DeliveryAssignment::getStatus).containsExactly(DeliveryStatus.DELIVERED);
        driverOne().get("/api/delivery/mine").then().body("orderId", not(hasItem(orderId)));
    }

    @Test
    void failedAttempt_goesBackOnTheList_andTheStockLeavesOnlyOnce() {
        String sku = newSku(10);
        String orderId = readyOrder(sku, 1);
        action(driverOne(), orderId, "take").statusCode(200);
        action(driverOne(), orderId, "pickup").statusCode(200);
        action(driverOne(), orderId, "fail", Map.of("reason", "Nobody home")).statusCode(200);

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        driverTwo().get("/api/delivery/available").then().body("orderId", hasItem(orderId));

        action(driverTwo(), orderId, "take").statusCode(200);
        action(driverTwo(), orderId, "pickup").statusCode(200);
        assertStock(sku, 9, 0);
        action(driverTwo(), orderId, "out-for-delivery").statusCode(200);
        action(driverTwo(), orderId, "deliver", Map.of("code", CODE)).statusCode(200);

        assertThat(attempts(orderId)).extracting(DeliveryAssignment::getStatus)
            .containsExactly(DeliveryStatus.FAILED, DeliveryStatus.DELIVERED);
        assertThat(attempts(orderId).get(0).getFailureReason()).isEqualTo("Nobody home");
    }

    @Test
    void afterFailedDeliveries_theAdminReturnsTheOrder_toStockAndRefundsIt() {
        String sku = newSku(10);
        String orderId = readyOrder(sku, 3);
        action(driverOne(), orderId, "take").statusCode(200);
        action(driverOne(), orderId, "pickup").statusCode(200);
        action(driverOne(), orderId, "fail", Map.of("reason", "Address does not exist")).statusCode(200);

        given().post("/api/admin/orders/" + orderId + "/return").then().statusCode(200).body("status", equalTo("RETURNED"));

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.RETURNED);
        assertThat(order(orderId).getRefundedAt()).isNotNull();
        assertStock(sku, 10, 0);
    }

    @Test
    void cancellingBeforePickup_freesTheDriver() {
        String orderId = readyOrder(newSku(10), 1);
        action(driverOne(), orderId, "take").statusCode(200);

        cancel(orderId).statusCode(200);

        assertThat(attempts(orderId)).extracting(DeliveryAssignment::getStatus).containsExactly(DeliveryStatus.CANCELLED);
        driverOne().get("/api/delivery/mine").then().body("orderId", not(hasItem(orderId)));
        driverOne().get("/api/delivery/available").then().body("orderId", not(hasItem(orderId)));
    }

    @Test
    void adminAssignsAndReassigns_butNotOnceTheGoodsArePickedUp() {
        String orderId = readyOrder(newSku(10), 1);

        assign(orderId, "DEL-001").statusCode(200);
        assign(orderId, "DEL-002").statusCode(200);
        driverOne().get("/api/delivery/mine").then().body("orderId", not(hasItem(orderId)));
        driverTwo().get("/api/delivery/mine").then().body("orderId", hasItem(orderId));

        action(driverTwo(), orderId, "pickup").statusCode(200);
        assign(orderId, "DEL-001").statusCode(409);
        assign(orderId, "CUST-100").statusCode(400);

        assertThat(attempts(orderId)).extracting(DeliveryAssignment::getStatus)
            .containsExactly(DeliveryStatus.REASSIGNED, DeliveryStatus.PICKED_UP);
    }

    @Test
    void assignedOrders_leaveTheWaitingList_andShowInProgress_withTheirDriver() {
        String orderId = readyOrder(newSku(10), 1);
        given().get("/api/admin/delivery/waiting").then().body("orderId", hasItem(orderId));

        assign(orderId, "DEL-001").statusCode(200);

        given().get("/api/admin/delivery/waiting").then().body("orderId", not(hasItem(orderId)));
        given().get("/api/admin/delivery/in-progress").then().statusCode(200)
            .body("find { it.orderId == '" + orderId + "' }.deliveryUserId", equalTo("DEL-001"))
            .body("find { it.orderId == '" + orderId + "' }.deliveryStatus", equalTo("ASSIGNED"));
        given().get("/api/admin/delivery/drivers").then()
            .body("find { it.userId == 'DEL-001' }.activeDeliveries", org.hamcrest.Matchers.greaterThanOrEqualTo(1));

        action(driverOne(), orderId, "pickup").statusCode(200);
        action(driverOne(), orderId, "out-for-delivery").statusCode(200);
        // The code's hash would give the code away: it never leaves the server
        given().get("/api/admin/delivery/orders/" + orderId + "/attempts").then().statusCode(200)
            .body("[0]", not(org.hamcrest.Matchers.hasKey("deliveryCodeHash")))
            .body("[0].deliveryUserId", equalTo("DEL-001"));
    }

    @Test
    void theCodeCannotBeGuessed() {
        String orderId = readyOrder(newSku(10), 1);
        action(driverOne(), orderId, "take").statusCode(200);
        action(driverOne(), orderId, "pickup").statusCode(200);
        action(driverOne(), orderId, "out-for-delivery").statusCode(200);

        action(driverOne(), orderId, "deliver", Map.of("code", "999990")).statusCode(400);
        driverOne().get("/api/delivery/mine").then()
            .body("find { it.orderId == '" + orderId + "' }.codeAttemptsLeft", equalTo(4))
            .body("find { it.orderId == '" + orderId + "' }.items[0].name", equalTo("Test product"));
        for (int i = 1; i < 5; i++) {
            action(driverOne(), orderId, "deliver", Map.of("code", "99999" + i)).statusCode(400);
        }
        driverOne().get("/api/delivery/mine").then()
            .body("find { it.orderId == '" + orderId + "' }.codeAttemptsLeft", equalTo(0));
        action(driverOne(), orderId, "deliver", Map.of("code", CODE)).statusCode(409);
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void cashOnDelivery_theDriverConfirmsTheCashBeforeTheCodeCounts() {
        String sku = newSku(10);
        setPrice(sku, 640.0);
        String orderId = asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1)), "paymentMethod", "COD"))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);

        // The driver sees the cash to collect before taking it
        driverOne().get("/api/delivery/available").then()
            .body("find { it.orderId == '" + orderId + "' }.amountToCollect", equalTo(640.00f))
            .body("find { it.orderId == '" + orderId + "' }.paymentMethod", equalTo("COD"));
        action(driverOne(), orderId, "take").statusCode(200);
        action(driverOne(), orderId, "pickup").statusCode(200);
        action(driverOne(), orderId, "out-for-delivery").statusCode(200);

        action(driverOne(), orderId, "deliver", Map.of("code", CODE)).statusCode(400)
            .body("reason", equalTo("Collect ₹640.00 in cash before handing over the parcel"));
        driverOne().get("/api/delivery/mine").then()
            .body("find { it.orderId == '" + orderId + "' }.codeAttemptsLeft", equalTo(5));
        assertThat(order(orderId).getPaymentTransactionId()).isNull();

        action(driverOne(), orderId, "deliver", Map.of("code", CODE, "cashCollected", true)).statusCode(200)
            .body("amountToCollect", org.hamcrest.Matchers.nullValue());
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order(orderId).getPaymentGateway()).isEqualTo("CASH_ON_DELIVERY");
    }

    @Test
    void driversSeeTheirOwnFigures_andAdminsSeeEveryDriver() {
        var before = driverOne().get("/api/delivery/stats").then().statusCode(200).extract().jsonPath();
        long deliveredBefore = before.getLong("periods.DAY.figures.delivered");
        long failedBefore = before.getLong("periods.DAY.figures.failed");
        double earnedBefore = before.getDouble("periods.DAY.figures.earnings");
        long otherBefore = driverTwo().get("/api/delivery/stats").then().extract().jsonPath().getLong("periods.YEAR.figures.completed");

        String sku = newSku(10);
        for (int i = 0; i < 2; i++) {
            String orderId = readyOrder(sku, 1);
            action(driverOne(), orderId, "take").statusCode(200);
            action(driverOne(), orderId, "pickup").statusCode(200);
            action(driverOne(), orderId, "out-for-delivery").statusCode(200);
            action(driverOne(), orderId, "deliver", Map.of("code", CODE)).statusCode(200);
        }
        String failing = readyOrder(sku, 1);
        action(driverOne(), failing, "take").statusCode(200);
        action(driverOne(), failing, "pickup").statusCode(200);
        action(driverOne(), failing, "fail", Map.of("reason", "Nobody home")).statusCode(200);
        String open = readyOrder(sku, 1);
        action(driverOne(), open, "take").statusCode(200);

        var after = driverOne().get("/api/delivery/stats").then().statusCode(200)
            .body("driverId", equalTo("DEL-001"))
            .body("openNow.deliveries", org.hamcrest.Matchers.greaterThanOrEqualTo(1))
            .extract().jsonPath();
        for (String period : List.of("DAY", "WEEK", "MONTH", "YEAR")) {
            assertThat(after.getLong("periods." + period + ".figures.delivered"))
                .as(period).isGreaterThanOrEqualTo(deliveredBefore + 2);
        }
        assertThat(after.getLong("periods.DAY.figures.delivered")).isEqualTo(deliveredBefore + 2);
        assertThat(after.getLong("periods.DAY.figures.failed")).isEqualTo(failedBefore + 1);
        // ₹40 a delivery, ₹15 for the visit that failed at the door
        assertThat(after.getDouble("periods.DAY.figures.earnings")).isEqualTo(earnedBefore + 95.0);
        assertThat(after.getLong("periods.DAY.figures.tasks")).isGreaterThanOrEqualTo(4);
        assertThat(after.getDouble("periods.DAY.figures.successRate")).isBetween(0.0, 1.0);
        // Another driver's figures do not move
        assertThat(driverTwo().get("/api/delivery/stats").then().extract().jsonPath().getLong("periods.YEAR.figures.completed"))
            .isEqualTo(otherBefore);

        // Today's chart: one bar an hour, and today's deliveries in them
        var day = driverOne().get("/api/delivery/stats/history?period=DAY").then().statusCode(200)
            .body("buckets", org.hamcrest.Matchers.hasSize(24)).extract().jsonPath();
        assertThat(day.getList("buckets.delivered", Integer.class).stream().mapToInt(Integer::intValue).sum())
            .isEqualTo(deliveredBefore + 2);
        driverOne().get("/api/delivery/stats/history?period=YEAR").then().body("buckets", org.hamcrest.Matchers.hasSize(12));
        driverOne().get("/api/delivery/stats/history?period=CENTURY").then().statusCode(400);

        given().get("/api/admin/delivery/drivers/stats?period=DAY").then().statusCode(200)
            .body("drivers.find { it.driverId == 'DEL-001' }.figures.delivered", equalTo((int) deliveredBefore + 2));
        given().get("/api/admin/delivery/drivers/DEL-001/stats").then().statusCode(200);
        driverOne().get("/api/admin/delivery/drivers/stats").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/delivery/stats").then().statusCode(403);
    }

    @Test
    void theCustomerRatesTheDelivery_andTheDriverSeesIt() {
        String orderId = readyOrder(newSku(10), 1);
        RequestSpecification alice = asUser(TestTokens.ALICE, "CUSTOMER");
        // Not delivered yet
        alice.get("/api/delivery-ratings/" + orderId).then().statusCode(200).body("canRate", equalTo(false));

        action(driverOne(), orderId, "take").statusCode(200);
        action(driverOne(), orderId, "pickup").statusCode(200);
        action(driverOne(), orderId, "out-for-delivery").statusCode(200);
        action(driverOne(), orderId, "deliver", Map.of("code", CODE)).statusCode(200);
        long ratedBefore = driverOne().get("/api/delivery/stats").then().extract().jsonPath().getLong("periods.DAY.figures.ratingCount");

        alice.get("/api/delivery-ratings/" + orderId).then().statusCode(200)
            .body("canRate", equalTo(true)).body("driverName", equalTo("Dev")).body("rating", org.hamcrest.Matchers.nullValue());
        alice.contentType(ContentType.JSON).body(Map.of("rating", 6)).put("/api/delivery-ratings/" + orderId).then().statusCode(400);
        alice.contentType(ContentType.JSON).body(Map.of("rating", 3, "comment", "Late"))
            .put("/api/delivery-ratings/" + orderId).then().statusCode(200).body("rating", equalTo(3));
        // Changed within the window: still one rating
        alice.contentType(ContentType.JSON).body(Map.of("rating", 5, "comment", "  Polite and careful  "))
            .put("/api/delivery-ratings/" + orderId).then().statusCode(200)
            .body("rating", equalTo(5)).body("comment", equalTo("Polite and careful"));

        // Someone else's order is not there for them; drivers and admins do not rate
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/delivery-ratings/" + orderId).then().statusCode(404);
        driverOne().get("/api/delivery-ratings/" + orderId).then().statusCode(403);

        var stats = driverOne().get("/api/delivery/stats").then().statusCode(200).extract().jsonPath();
        assertThat(stats.getLong("periods.DAY.figures.ratingCount")).isEqualTo(ratedBefore + 1);
        assertThat(stats.getList("feedback.comment")).contains("Polite and careful");
        assertThat(stats.getString("feedback")).doesNotContain("CUST-100");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String readyOrder(String sku, int quantity) {
        String orderId = placeOrder(sku, quantity, 25.0);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        return orderId;
    }

    private io.restassured.specification.RequestSpecification driverOne() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }

    private io.restassured.specification.RequestSpecification driverTwo() {
        return asUser(TestTokens.DRIVER_TWO, "DELIVERY");
    }

    private ValidatableResponse action(io.restassured.specification.RequestSpecification driver, String orderId, String action) {
        return action(driver, orderId, action, Map.of());
    }

    private ValidatableResponse action(io.restassured.specification.RequestSpecification driver, String orderId,
                                       String action, Map<String, ?> body) {
        return driver.contentType(ContentType.JSON).body(body)
                .post("/api/delivery/orders/" + orderId + "/" + action).then();
    }

    private ValidatableResponse assign(String orderId, String driverUserId) {
        return given().contentType(ContentType.JSON).body(Map.of("deliveryUserId", driverUserId))
                .post("/api/admin/delivery/orders/" + orderId + "/assign").then();
    }

    private List<DeliveryAssignment> attempts(String orderId) {
        return assignments.findByOrderIdOrderByAttemptNumberAsc(orderId);
    }
}
