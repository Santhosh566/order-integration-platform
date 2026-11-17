package com.example.orders;

import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Admins turn customers and drivers off and on. An inactive user still signs in, but the API
 * refuses everything except /api/me and the public catalogue.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class AccountStatusIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void anInactiveCustomerSignsIn_butCannotUseTheApp_untilTurnedBackOn() {
        String bobId = bob().get("/api/me").then().statusCode(200).body("active", equalTo(true)).extract().path("userId");
        bob().get("/api/orders").then().statusCode(200);
        try {
            deactivate(bobId, "").statusCode(400).body("reason", containsString("reason"));
            deactivate(bobId, "Repeated chargebacks").statusCode(200)
                .body("active", equalTo(false));
            deactivate(bobId, "Again").statusCode(409);

            // Everything is refused with the same message, except /api/me and the public shop
            bob().get("/api/orders").then().statusCode(403)
                .body("status", equalTo("ACCOUNT_INACTIVE"))
                .body("reason", equalTo("Your account is inactive. Please contact the administrator."));
            bob().contentType(ContentType.JSON).body(Map.of("items", List.of(Map.of("sku", newSku(5), "quantity", 1))))
                .post("/api/orders").then().statusCode(403);
            bob().get("/api/me/profile").then().statusCode(403);
            bob().get("/api/wishlist").then().statusCode(403);
            bob().get("/api/me").then().statusCode(200).body("active", equalTo(false));
            anonymous().get("/api/public/catalog").then().statusCode(200);

            // Admins see who did it and why; the list can show only inactive users
            given().get("/api/admin/users/" + bobId).then().statusCode(200)
                .body("active", equalTo(false))
                .body("deactivation.reason", equalTo("Repeated chargebacks"))
                .body("deactivation.byName", notNullValue())
                .body("deactivation.at", notNullValue());
            given().get("/api/admin/users?active=false&size=100").then().statusCode(200)
                .body("items.userId", hasItem(bobId));
            given().get("/api/admin/users?active=true&size=100").then().statusCode(200)
                .body("items.userId", not(hasItem(bobId)));
            assertThat(jdbc.queryForObject("select count(*) from customer_notifications where customer_id = ? "
                + "and subject = 'Your account has been paused'", Integer.class, bobId)).isGreaterThanOrEqualTo(1);
        } finally {
            given().post("/api/admin/users/" + bobId + "/activate").then().statusCode(200).body("active", equalTo(true));
        }
        bob().get("/api/orders").then().statusCode(200);
        bob().get("/api/me").then().body("active", equalTo(true));
        given().get("/api/admin/users/" + bobId).then().body("deactivation", nullValue());
        given().post("/api/admin/users/" + bobId + "/activate").then().statusCode(409);
    }

    @Test
    void onlyCustomersAndDriversCanBeTurnedOff_andNotByThemselves() {
        String adminId = given().get("/api/me").then().extract().path("userId");
        deactivate(adminId, "Testing").statusCode(409).body("reason", containsString("your own"));
        String serviceId = asUser(TestTokens.WAREHOUSE, "SERVICE").get("/api/me").then().extract().path("userId");
        deactivate(serviceId, "Testing").statusCode(409).body("reason", containsString("Keycloak"));
        deactivate("NO-SUCH-USER", "Testing").statusCode(404);
        // Customers and drivers cannot do it at all
        String bobId = bob().get("/api/me").then().extract().path("userId");
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("reason", "x y z"))
            .post("/api/admin/users/" + bobId + "/deactivate").then().statusCode(403);
    }

    @Test
    void turningADriverOff_givesBackJobsNotStarted_butWaitsForAParcelTheyCarry() {
        String driverId = driverTwo().get("/api/me").then().extract().path("userId");
        String waiting = readyOrder();
        String carried = readyOrder();
        action(waiting, "take");
        action(carried, "take");
        action(carried, "pickup");
        try {
            deactivate(driverId, "Licence expired").statusCode(409)
                .body("reason", containsString("carrying order " + carried));
            // Nothing changed: the refusal rolled everything back
            driverTwo().get("/api/delivery/mine").then().statusCode(200).body("orderId", hasItem(waiting));

            action(carried, "out-for-delivery");
            action(carried, "fail", Map.of("reason", "Nobody home"));

            deactivate(driverId, "Licence expired").statusCode(200)
                .body("deliveriesReleased", equalTo(1));
            // Back on the list for other drivers; the inactive driver is refused
            asUser(TestTokens.DRIVER_ONE, "DELIVERY").get("/api/delivery/available").then().statusCode(200)
                .body("orderId", hasItem(waiting));
            driverTwo().get("/api/delivery/mine").then().statusCode(403).body("status", equalTo("ACCOUNT_INACTIVE"));
            // And an admin cannot hand them work
            given().contentType(ContentType.JSON).body(Map.of("deliveryUserId", driverId))
                .post("/api/admin/delivery/orders/" + waiting + "/assign").then().statusCode(400);
        } finally {
            given().post("/api/admin/users/" + driverId + "/activate").then().statusCode(200);
        }
        driverTwo().get("/api/delivery/mine").then().statusCode(200);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RequestSpecification bob() {
        return asUser(TestTokens.BOB, "CUSTOMER");
    }

    private RequestSpecification driverTwo() {
        return asUser(TestTokens.DRIVER_TWO, "DELIVERY");
    }

    private io.restassured.response.ValidatableResponse deactivate(String userId, String reason) {
        return given().contentType(ContentType.JSON).body(Map.of("reason", reason))
            .post("/api/admin/users/" + userId + "/deactivate").then();
    }

    private String readyOrder() {
        String orderId = placeOrder(newSku(5), 1, 100.0);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        return orderId;
    }

    private void action(String orderId, String action) {
        action(orderId, action, Map.of());
    }

    private void action(String orderId, String action, Map<String, ?> body) {
        driverTwo().contentType(ContentType.JSON).body(body).post("/api/delivery/orders/" + orderId + "/" + action)
            .then().statusCode(200);
    }
}
