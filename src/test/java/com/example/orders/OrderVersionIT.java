package com.example.orders;

import com.example.orders.model.OrderStatus;
import com.example.orders.service.DeliveryCodeGenerator;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.doReturn;

/**
 * A person's action carries the order version they were shown (If-Match). When the order changed
 * after they opened it, the action is refused with 412 and what it is now, and nothing is done.
 * Without If-Match an action works on the order as it is, as partners and older callers expect.
 * The same settings as PartialCancelIT, so the test context is shared: orders wait for a driver.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class OrderVersionIT extends IntegrationTestBase {

    private static final String CODE = "123456";

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixedCode() {
        doReturn(CODE).when(codeGenerator).next();
    }

    @Test
    void anOrderShowsItsVersion_andEveryChangeRaisesIt() {
        String orderId = waitingOrder(2);
        long seen = version(alice(), orderId);
        assertThat(seen).isEqualTo(order(orderId).getVersion());

        cancelItems(orderId, 1, null).statusCode(200);
        long after = version(alice(), orderId);
        assertThat(after).isGreaterThan(seen);
        assertThat(order(orderId).getVersion()).isEqualTo(after);
    }

    @Test
    void cancellingAnOrderThatChangedSinceItWasOpened_isRefused_withWhatItIsNow() {
        String orderId = waitingOrder(2);
        long seen = version(alice(), orderId);

        // Meanwhile one unit is cancelled (say, from the customer's phone)
        cancelItems(orderId, 1, null).statusCode(200);
        long now = order(orderId).getVersion();

        // The laptop, still showing the old order, asks to cancel it
        alice().header("If-Match", "\"" + seen + "\"").post("/api/orders/" + orderId + "/cancel").then()
            .statusCode(412)
            .header("ETag", "\"" + now + "\"")
            .body("status", equalTo("CHANGED"))
            .body("orderStatus", equalTo("READY_FOR_DISPATCH"))
            .body("version", equalTo((int) now))
            .body("reason", containsString("changed after you opened it"));
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.READY_FOR_DISPATCH);

        // After a reload it goes through
        alice().header("If-Match", "\"" + now + "\"").post("/api/orders/" + orderId + "/cancel").then()
            .statusCode(200)
            .body("status", equalTo("CANCELLED"))
            .body("version", equalTo((int) order(orderId).getVersion().longValue()));
        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancellingItemsTwiceFromTheSameScreen_takesOffOnlyTheFirst() {
        String orderId = waitingOrder(3);
        long seen = version(alice(), orderId);

        cancelItems(orderId, 1, seen).statusCode(200);
        // A double click, or a second tab: same version, so refused rather than taking off a second unit
        cancelItems(orderId, 1, seen).statusCode(412).body("status", equalTo("CHANGED"));
        assertThat(order(orderId).getItems().get(0).getCancelledQuantity()).isEqualTo(1);

        // Weak tags, as some proxies send them, and "*" count too; nonsense is a bad request
        long now = order(orderId).getVersion();
        cancelItems(orderId, 1, "W/\"" + now + "\"").statusCode(200);
        cancelItems(orderId, 2, "*").statusCode(400).body("reason", containsString("Only 1"));
        cancelItems(orderId, 1, "yesterday").statusCode(400).body("reason", containsString("If-Match"));
    }

    @Test
    void assigningADriverToAnOrderSomeoneElseAssigned_isRefused() {
        String orderId = waitingOrder(1);
        long seen = version(given(), orderId);

        assign(orderId, "DEL-001", null).statusCode(200);
        // A second admin, with the order still showing "waiting for a driver"
        assign(orderId, "DEL-002", seen).statusCode(412).body("status", equalTo("CHANGED"));
        assertThat(jdbc.queryForObject("select delivery_user_id from delivery_assignments where order_id = ? "
                + "and status = 'ASSIGNED'", String.class, orderId)).isEqualTo("DEL-001");
    }

    @Test
    void aGoodwillRefundOnAnOrderThatChanged_isRefused_andNothingIsPaid() {
        String orderId = waitingOrder(2);
        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take").then()
            .statusCode(200);
        deliver(orderId);
        long seen = version(given(), orderId);

        refund(orderId, 100, seen).statusCode(200).body("kind", equalTo("GOODWILL"));
        // The same form sent again, on the version from before the first refund
        refund(orderId, 100, seen).statusCode(412).body("status", equalTo("CHANGED"));
        assertThat(jdbc.queryForObject("select count(*) from credit_notes where order_id = ? and kind = 'GOODWILL'",
                Integer.class, orderId)).isEqualTo(1);

        // Without If-Match an admin acts on the order as it is now
        refund(orderId, 50, null).statusCode(200);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification driver() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }

    /** An order of one ₹1,180 product, waiting at the dock for a driver. */
    private String waitingOrder(int quantity) {
        String sku = newSku(10);
        setPrice(sku, 1180.00);
        String orderId = alice().contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", quantity))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        return orderId;
    }

    /** The version as the reader is shown it, which the body and the ETag agree on. */
    private long version(RequestSpecification who, String orderId) {
        var response = who.get("/api/orders/" + orderId).then().statusCode(200).extract();
        long version = ((Number) response.path("version")).longValue();
        assertThat(response.header("ETag")).isEqualTo("\"" + version + "\"");
        return version;
    }

    private RequestSpecification ifMatch(RequestSpecification request, Object version) {
        if (version == null) {
            return request;
        }
        return request.header("If-Match", version instanceof Long ? "\"" + version + "\"" : version.toString());
    }

    private ValidatableResponse cancelItems(String orderId, int quantity, Object version) {
        String sku = order(orderId).getItems().get(0).getSku();
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("sku", sku, "quantity", quantity)));
        return ifMatch(alice(), version).contentType(ContentType.JSON).body(body)
            .post("/api/orders/" + orderId + "/cancel-items").then();
    }

    private ValidatableResponse assign(String orderId, String driverUserId, Long version) {
        return ifMatch(given(), version).contentType(ContentType.JSON).body(Map.of("deliveryUserId", driverUserId))
            .post("/api/admin/delivery/orders/" + orderId + "/assign").then();
    }

    private ValidatableResponse refund(String orderId, int amount, Long version) {
        return ifMatch(given(), version).contentType(ContentType.JSON)
            .body(Map.of("amount", amount, "reason", "Scratched on arrival"))
            .post("/api/admin/orders/" + orderId + "/refunds").then();
    }

    private void deliver(String orderId) {
        String base = "/api/delivery/orders/" + orderId + "/";
        driver().contentType(ContentType.JSON).body(Map.of()).post(base + "pickup").then().statusCode(200);
        driver().contentType(ContentType.JSON).body(Map.of()).post(base + "out-for-delivery").then().statusCode(200);
        driver().contentType(ContentType.JSON).body(Map.of("code", CODE)).post(base + "deliver").then().statusCode(200);
        awaitStatus(orderId, OrderStatus.DELIVERED);
    }
}
