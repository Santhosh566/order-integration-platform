package com.example.orders;

import com.example.orders.model.AppUser;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.Role;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.security.Viewer;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Who may call what, and that customers only ever see their own orders.
 */
class SecurityIT extends IntegrationTestBase {

    @Autowired AppUserRepository users;

    @Test
    void withoutAToken_onlyThePageAndHealthAreOpen() {
        anonymous().get("/").then().statusCode(200);
        anonymous().get("/api/public/auth-config").then().statusCode(200).body("clientId", equalTo("order-platform-web"));
        // Reachable without a token; 503 only means a component reports DOWN
        anonymous().get("/actuator/health").then().statusCode(anyOf(is(200), is(503)));

        anonymous().get("/api/orders").then().statusCode(401);
        anonymous().contentType(ContentType.JSON).body(Map.of("items", List.of())).post("/api/orders").then().statusCode(401);
        anonymous().get("/api/stream/orders").then().statusCode(401);
        anonymous().get("/api/dlq").then().statusCode(401);
        anonymous().post("/api/admin/routes/order-event-consumer/stop").then().statusCode(401);
    }

    @Test
    void aForgedToken_isRejected() {
        String token = TestTokens.issue(TestTokens.ADMIN, "ADMIN");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        anonymous().header("Authorization", "Bearer " + tampered).get("/api/orders").then().statusCode(401);
    }

    @Test
    void aCustomerAlwaysOrdersForThemselves() {
        String sku = newSku(10);

        String orderId = asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("customerId", "CUST-101", "items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");

        assertThat(order(orderId).getCustomerId()).isEqualTo("CUST-100");
    }

    @Test
    void customersSeeOnlyTheirOwnOrders() {
        String sku = newSku(10);
        String alicesOrder = asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(alicesOrder, OrderStatus.DELIVERED);

        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/orders/" + alicesOrder).then().statusCode(200);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/orders").then().statusCode(200)
            .body("order_id", hasItem(alicesOrder));

        // To Bob, Alice's order does not exist
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/orders/" + alicesOrder).then().statusCode(404);
        asUser(TestTokens.BOB, "CUSTOMER").post("/api/orders/" + alicesOrder + "/cancel").then().statusCode(404);
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/invoices/" + alicesOrder).then().statusCode(404);
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/orders").then().statusCode(200)
            .body("order_id", not(hasItem(alicesOrder)));

        // The admin sees everything
        given().get("/api/orders/" + alicesOrder).then().statusCode(200);
    }

    @Test
    void eachRole_isKeptToItsOwnArea() {
        var customer = asUser(TestTokens.ALICE, "CUSTOMER");
        customer.get("/api/admin/metrics").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/dlq").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").delete("/api/orders/ORD-ANY").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("orderId", "ORD-ANY"))
            .post("/api/fulfillment/payment-captured").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").contentType("text/csv").body("x").post("/api/orders/csv").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/delivery/available").then().statusCode(403);

        asUser(TestTokens.DRIVER_ONE, "DELIVERY").get("/api/orders").then().statusCode(403);
        asUser(TestTokens.DRIVER_ONE, "DELIVERY").contentType(ContentType.JSON)
            .body(Map.of("items", List.of())).post("/api/orders").then().statusCode(403);
        asUser(TestTokens.DRIVER_ONE, "DELIVERY").get("/api/delivery/available").then().statusCode(200);

        // The warehouse system may send signals, nothing else
        asUser(TestTokens.WAREHOUSE, "SERVICE").contentType(ContentType.JSON).body(Map.of("orderId", "ORD-DOES-NOT-EXIST"))
            .post("/api/fulfillment/warehouse-packed").then().statusCode(404);
        asUser(TestTokens.WAREHOUSE, "SERVICE").get("/api/orders").then().statusCode(403);
    }

    @Test
    void someoneNewInKeycloak_getsAUserHere_andCanOrderOnceTheyHaveAnAddress() {
        String subject = UUID.randomUUID().toString();
        String email = "new-" + subject.substring(0, 8) + "@example.com";
        var newCustomer = (java.util.function.Supplier<io.restassured.specification.RequestSpecification>) () ->
                anonymous().header("Authorization", "Bearer " + TestTokens.issue(subject, email, true, "CUSTOMER"));

        String userId = newCustomer.get().get("/api/me").then().statusCode(200)
            .body("roles", hasItem("CUSTOMER")).extract().path("userId");
        assertThat(userId).startsWith("CUST-");
        assertThat(users.findById(userId).orElseThrow().getEmail()).isEqualTo(email);

        String sku = newSku(5);
        newCustomer.get().contentType(ContentType.JSON).body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(400).body("reason", containsString("address"));

        newCustomer.get().contentType(ContentType.JSON).body(Map.of("recipientName", "New Customer",
                "recipientPhone", "+91-98450-00177", "line1", "5 Elm Street", "city", "Chennai", "state", "Tamil Nadu",
                "postalCode", "600002", "country", "IN"))
            .put("/api/me/address").then().statusCode(200);

        String orderId = newCustomer.get().contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        Order order = order(orderId);
        assertThat(order.getCustomerId()).isEqualTo(userId);
        assertThat(order.getShippingAddress().getCity()).isEqualTo("Chennai");
    }

    @Test
    void anUnverifiedEmail_isNeverLinkedToSomeoneElsesAccount() {
        // Alice's email, claimed by a different Keycloak user who has not proven it
        String subject = UUID.randomUUID().toString();
        String userId = anonymous().header("Authorization", "Bearer "
                + TestTokens.issue(subject, "alice.smith@example.com", false, "CUSTOMER"))
            .get("/api/me").then().statusCode(200).extract().path("userId");

        assertThat(userId).isNotEqualTo("CUST-100");
        AppUser user = users.findById(userId).orElseThrow();
        assertThat(user.getEmail()).isNull();
    }

    @Test
    void rolesFollowKeycloak() {
        String subject = UUID.randomUUID().toString();
        String userId = asUser(subject, "CUSTOMER").get("/api/me").then().extract().path("userId");

        // An admin made them a driver in Keycloak
        asUser(subject, "DELIVERY").get("/api/me").then().statusCode(200).body("userId", equalTo(userId));
        asUser(subject, "DELIVERY").get("/api/delivery/available").then().statusCode(200);

        assertThat(users.findById(userId).orElseThrow().getRoles()).containsExactly(Role.DELIVERY);
    }

    @Test
    void theLiveStream_onlyCarriesWhatTheViewerMaySee() throws Exception {
        Method visibleTo = com.example.orders.service.OrderEventBroadcaster.class
                .getDeclaredMethod("visibleTo", Viewer.class, Object.class);
        visibleTo.setAccessible(true);
        Order alicesOrder = new Order();
        alicesOrder.setCustomerId("CUST-100");
        Viewer alice = new Viewer("CUST-100", EnumSet.of(Role.CUSTOMER));
        Viewer bob = new Viewer("CUST-101", EnumSet.of(Role.CUSTOMER));
        Viewer admin = new Viewer("ADM-001", EnumSet.of(Role.ADMIN));

        assertThat((boolean) visibleTo.invoke(null, alice, alicesOrder)).isTrue();
        assertThat((boolean) visibleTo.invoke(null, bob, alicesOrder)).isFalse();
        assertThat((boolean) visibleTo.invoke(null, admin, alicesOrder)).isTrue();
        assertThat((boolean) visibleTo.invoke(null, alice, Map.of("dlq", "event"))).isFalse();
    }
}
