package com.example.orders;

import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * What the admin console reads: the order search, counts by status, and that customers do not
 * see the staff-only fields of their own orders.
 */
class AdminConsoleIT extends IntegrationTestBase {

    @Test
    void searchFindsAnOrder_byPartOfItsId_andFiltersByStatus() {
        String orderId = placeOrder(newSku(10), 1, 25.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        String part = orderId.substring(4, 12).toUpperCase();

        given().queryParam("q", part).get("/api/admin/orders").then().statusCode(200)
            .body("items.orderId", hasItem(orderId))
            .body("items[0].customerId", equalTo("CUST-100"))
            .body("items[0].recipientName", notNullValue())
            .body("items[0]", hasKey("fraudStatus"));

        given().queryParam("q", part).queryParam("status", "CANCELLED,DELIVERED").get("/api/admin/orders").then()
            .statusCode(200).body("items.orderId", not(hasItem(orderId)));

        given().queryParam("status", "SHIPPED, READY_FOR_DISPATCH").queryParam("size", 5).get("/api/admin/orders").then()
            .statusCode(200)
            .body("size", equalTo(5))
            .body("items.status", everyItem(org.hamcrest.Matchers.oneOf("SHIPPED", "READY_FOR_DISPATCH")));

        given().queryParam("status", "LOST").get("/api/admin/orders").then().statusCode(400);
    }

    @Test
    void statusCounts_listEveryStatus() {
        String orderId = placeOrder(newSku(10), 1, 25.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        cancel(orderId).statusCode(200);

        given().get("/api/admin/orders/status-counts").then().statusCode(200)
            .body("$", hasKey("FRAUD_REJECTED"))
            .body("CANCELLED", greaterThanOrEqualTo(1));
    }

    @Test
    void theAdminConsoleIsForAdminsOnly() {
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/admin/orders").then().statusCode(403);
        asUser(TestTokens.DRIVER_ONE, "DELIVERY").get("/api/admin/delivery/drivers").then().statusCode(403);
    }

    @Test
    void aCustomerDoesNotSeeFraudScoresOrOurCosts_butTheAdminDoes() {
        String sku = newSku(10);
        setPrice(sku, 25.0);
        String orderId = asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.SHIPPED);

        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/orders/" + orderId).then().statusCode(200)
            .body("orderId", equalTo(orderId))
            .body("total", equalTo(25.0f))
            .body("$", not(hasKey("fraudScore")))
            .body("$", not(hasKey("fraudStatus")))
            .body("$", not(hasKey("shippingCost")))
            .body("items[0].sku", equalTo(sku));

        // Standard orders are only screened, so the status may be empty; the admin still gets the field
        given().get("/api/orders/" + orderId).then().statusCode(200)
            .body("$", hasKey("fraudStatus"))
            .body("$", hasKey("shippingCost"));
    }

    @Test
    void theDriverListHasOnlyActiveDeliveryUsers() {
        given().get("/api/admin/delivery/drivers").then().statusCode(200)
            .body("userId", hasItem("DEL-001"))
            .body("userId", not(hasItem("CUST-100")))
            .body("activeDeliveries", everyItem(greaterThanOrEqualTo(0)));
    }
}
