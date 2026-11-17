package com.example.orders;

import com.example.orders.model.OrderStatus;
import com.example.orders.model.ReturnPickup;
import com.example.orders.repository.ReturnPickupRepository;
import com.example.orders.service.DeliveryCodeGenerator;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doReturn;

/**
 * A delivery user collects an approved return from the customer, who confirms the hand-over with a
 * one-time code; the refund follows the inspection at the hub.
 */
class ReturnPickupIT extends IntegrationTestBase {

    private static final String CODE = "424242";

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @Autowired ReturnPickupRepository pickups;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixedCode() {
        doReturn(CODE).when(codeGenerator).next();
    }

    @Test
    void aDriverCollectsTheReturnWithTheCustomersCode_thenTheHubRefundsIt() {
        String sku = newSku(10);
        String orderId = deliveredOrder(sku, 2);
        String returnId = approvedReturn(orderId, sku, 1);

        // On the list by area only, never the street or phone, until a driver takes it
        String list = driverOne().get("/api/delivery/pickups/available").then().statusCode(200)
            .body("returnId", hasItem(returnId)).extract().asString();
        assertThat(list).doesNotContain("4th Cross").doesNotContain("+91-98450-00101");

        pickup(driverOne(), returnId, "take").statusCode(200)
            .body("address.line1", equalTo("12, 4th Cross, Indiranagar"))
            .body("items[0].name", equalTo("Test product"))
            .body("items[0].quantity", equalTo(1));
        pickup(driverTwo(), returnId, "take").statusCode(409);
        alice().get("/api/returns/" + returnId).then().body("pickupStatus", equalTo("ASSIGNED"));

        pickup(driverOne(), returnId, "start").statusCode(200).body("pickupStatus", equalTo("ON_THE_WAY"));
        assertThat(subjects(orderId)).contains("We are on the way to collect your return");

        pickup(driverOne(), returnId, "collect", Map.of("code", "000000")).statusCode(400);
        driverOne().get("/api/delivery/pickups/mine").then()
            .body("find { it.returnId == '" + returnId + "' }.codeAttemptsLeft", equalTo(4));
        pickup(driverOne(), returnId, "collect", Map.of("code", CODE)).statusCode(200)
            .body("returnStatus", equalTo("PICKED_UP"));

        alice().get("/api/returns/" + returnId).then()
            .body("status", equalTo("PICKED_UP"))
            .body("pickedUpBy", equalTo("DEL-001"))
            .body("pickupStatus", nullValue());
        assertThat(subjects(orderId)).contains("We collected your return " + returnId);
        driverOne().get("/api/delivery/pickups/mine").then().body("returnId", not(hasItem(returnId)));
        driverOne().get("/api/delivery/pickups/available").then().body("returnId", not(hasItem(returnId)));

        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/receive")
            .then().statusCode(200).body("number", org.hamcrest.Matchers.startsWith("CN/"))
            .body("returnId", equalTo(returnId));
        alice().get("/api/returns/" + returnId).then().body("status", equalTo("REFUNDED"));
        assertStock(sku, 9, 0);
    }

    @Test
    void aMissedPickup_goesBackOnTheList_andTheCustomerIsTold() {
        String sku = newSku(10);
        String orderId = deliveredOrder(sku, 1);
        String returnId = approvedReturn(orderId, sku, 1);

        pickup(driverOne(), returnId, "take").statusCode(200);
        pickup(driverOne(), returnId, "start").statusCode(200);
        pickup(driverOne(), returnId, "fail", Map.of("reason", "Nobody home")).statusCode(200);

        assertThat(subjects(orderId)).contains("We could not collect your return " + returnId);
        driverTwo().get("/api/delivery/pickups/available").then()
            .body("find { it.returnId == '" + returnId + "' }.attempts", equalTo(1));
        // The old code died with the failed attempt
        pickup(driverTwo(), returnId, "take").statusCode(200);
        pickup(driverTwo(), returnId, "start").statusCode(200);
        pickup(driverTwo(), returnId, "collect", Map.of("code", CODE)).statusCode(200);

        assertThat(pickups.findByReturnIdOrderByAttemptNumberAsc(returnId)).extracting(ReturnPickup::getStatus)
            .containsExactly(ReturnPickup.Status.FAILED, ReturnPickup.Status.COLLECTED);
    }

    @Test
    void theAdminAssignsAndReassigns_untilTheDriverSetsOut_andReceivingClosesAnOpenPickup() {
        String sku = newSku(10);
        String orderId = deliveredOrder(sku, 2);
        String returnId = approvedReturn(orderId, sku, 1);

        assign(returnId, "DEL-001").statusCode(200);
        given().get("/api/admin/pickups/in-progress").then()
            .body("find { it.returnId == '" + returnId + "' }.deliveryUserId", equalTo("DEL-001"));
        given().get("/api/admin/delivery/drivers").then()
            .body("find { it.userId == 'DEL-001' }.activeDeliveries", org.hamcrest.Matchers.greaterThanOrEqualTo(1));
        assign(returnId, "DEL-002").statusCode(200);
        pickup(driverTwo(), returnId, "start").statusCode(200);
        assign(returnId, "DEL-001").statusCode(409);
        assign(returnId, "CUST-100").statusCode(400);

        // The customer posted it instead: receiving it frees the driver
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/receive")
            .then().statusCode(200);
        assertThat(pickups.findByReturnIdOrderByAttemptNumberAsc(returnId)).extracting(ReturnPickup::getStatus)
            .containsExactly(ReturnPickup.Status.REASSIGNED, ReturnPickup.Status.CANCELLED);
        driverTwo().get("/api/delivery/pickups/mine").then().body("returnId", not(hasItem(returnId)));
    }

    @Test
    void onlyDriversCollect_andOnlyApprovedReturns() {
        String sku = newSku(10);
        String orderId = deliveredOrder(sku, 1);
        String requested = alice().contentType(ContentType.JSON)
            .body(Map.of("orderId", orderId, "reason", "Too small", "items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/returns").then().statusCode(201).extract().path("returnId");

        pickup(driverOne(), requested, "take").statusCode(409);
        alice().get("/api/delivery/pickups/available").then().statusCode(403);
        asUser(TestTokens.DRIVER_ONE, "DELIVERY").get("/api/admin/pickups/waiting").then().statusCode(403);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification driverOne() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }

    private RequestSpecification driverTwo() {
        return asUser(TestTokens.DRIVER_TWO, "DELIVERY");
    }

    private String deliveredOrder(String sku, int quantity) {
        String orderId = alice().contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", quantity))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.DELIVERED);
        return orderId;
    }

    private String approvedReturn(String orderId, String sku, int quantity) {
        String returnId = alice().contentType(ContentType.JSON)
            .body(Map.of("orderId", orderId, "reason", "Does not fit", "items", List.of(Map.of("sku", sku, "quantity", quantity))))
            .post("/api/returns").then().statusCode(201).extract().path("returnId");
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/approve")
            .then().statusCode(200);
        return returnId;
    }

    private ValidatableResponse pickup(RequestSpecification driver, String returnId, String action) {
        return pickup(driver, returnId, action, Map.of());
    }

    private ValidatableResponse pickup(RequestSpecification driver, String returnId, String action, Map<String, String> body) {
        return driver.contentType(ContentType.JSON).body(body)
            .post("/api/delivery/pickups/" + returnId + "/" + action).then();
    }

    private ValidatableResponse assign(String returnId, String driverId) {
        return given().contentType(ContentType.JSON).body(Map.of("deliveryUserId", driverId))
            .post("/api/admin/pickups/" + returnId + "/assign").then();
    }

    private List<String> subjects(String orderId) {
        return jdbc.queryForList("select subject from customer_notifications where order_id = ?", String.class, orderId);
    }
}
