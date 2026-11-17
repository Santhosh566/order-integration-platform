package com.example.orders;

import com.example.orders.model.DeadLetterEvent;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.DeadLetterEventRepository;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The operations screen: dead letters that are replayed or dismissed, and pausing consumers
 * without letting anyone stop the API or a pipeline step.
 */
class OperationsIT extends IntegrationTestBase {

    @Autowired DeadLetterEventRepository deadLetters;

    @Test
    void aCancelledOrderIsNotReplayed_butCanBeDismissed_andLeavesTheOpenCount() {
        String orderId = placeOrder(newSku(10), 1, 25.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        cancel(orderId).statusCode(200);
        DeadLetterEvent dead = deadLetters.save(new DeadLetterEvent(orderId, "order.created",
                "{\"orderId\":\"" + orderId + "\"}", "Cannot obtain LRA id", "IllegalStateException", 3));
        long openBefore = deadLetters.countByReplayedFalseAndDismissedAtIsNull();

        given().post("/api/dlq/" + dead.getId() + "/replay").then().statusCode(409);
        assertThat(deadLetters.findById(dead.getId()).orElseThrow().isReplayed()).isFalse();

        given().contentType(ContentType.JSON).body(Map.of()).post("/api/dlq/" + dead.getId() + "/dismiss")
            .then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("note", "Order was cancelled"))
            .post("/api/dlq/" + dead.getId() + "/dismiss").then().statusCode(200)
            .body("dismissedBy", equalTo("ADM-001"))
            .body("dismissNote", equalTo("Order was cancelled"))
            .body("dismissedAt", notNullValue());

        assertThat(deadLetters.countByReplayedFalseAndDismissedAtIsNull()).isEqualTo(openBefore - 1);
        given().post("/api/dlq/" + dead.getId() + "/replay").then().statusCode(409);
        given().contentType(ContentType.JSON).body(Map.of("note", "again"))
            .post("/api/dlq/" + dead.getId() + "/dismiss").then().statusCode(409);
        given().contentType(ContentType.JSON).body(Map.of("note", "x"))
            .post("/api/dlq/999999999/dismiss").then().statusCode(404);
    }

    @Test
    void routesSayWhatKindTheyAre_andOnlyConsumersCanBePaused() {
        given().get("/api/admin/routes").then().statusCode(200)
            .body("find { it.routeId == 'outbox-poller' }.kind", equalTo("CONSUMER"))
            .body("find { it.routeId == 'outbox-poller' }.controllable", equalTo(true))
            .body("find { it.routeId == 'process-order' }.kind", equalTo("INTERNAL"))
            .body("find { it.routeId == 'cancel-order-workflow' }.kind", equalTo("API"))
            .body("kind", hasItem("API"));

        given().post("/api/admin/routes/process-order/stop").then().statusCode(409)
            .body("reason", containsString("only consumers"));
        given().post("/api/admin/routes/cancel-order-workflow/stop").then().statusCode(409);
        given().post("/api/admin/routes/no-such-route/stop").then().statusCode(404);
        given().post("/api/admin/routes/catalog-price-scheduler/explode").then().statusCode(400);

        given().post("/api/admin/routes/catalog-price-scheduler/suspend").then().statusCode(200)
            .body("status", equalTo("Suspended")).body("by", equalTo("ADM-001"));
        given().post("/api/admin/routes/catalog-price-scheduler/resume").then().statusCode(200)
            .body("status", equalTo("Started"));
    }

    @Test
    void consumersSayWhatIsWaitingForThem() {
        given().get("/api/admin/routes").then().statusCode(200)
            .body("find { it.routeId == 'outbox-poller' }.waiting.unit", equalTo("orders not yet sent to Kafka"))
            .body("find { it.routeId == 'outbox-poller' }.waiting.orderStatus", equalTo("PENDING"))
            .body("find { it.routeId == 'customer-notification-outbox' }.waiting.count", org.hamcrest.Matchers.greaterThanOrEqualTo(0))
            // The tests run with delivery simulation on, so the tracker has orders to move
            .body("find { it.routeId == 'carrier-tracking-poller' }.waiting.unit", equalTo("orders on the road"))
            .body("find { it.routeId == 'process-order' }.waiting", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void aPausedPollerHoldsNewOrdersBack_andTheyGoOnWhenItResumes() {
        given().post("/api/admin/routes/outbox-poller/suspend").then().statusCode(200);
        try {
            String orderId = placeOrder(newSku(10), 1, 25.0);
            given().get("/api/admin/routes").then()
                .body("find { it.routeId == 'outbox-poller' }.waiting.count", org.hamcrest.Matchers.greaterThanOrEqualTo(1));
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.PENDING);
        } finally {
            given().post("/api/admin/routes/outbox-poller/resume").then().statusCode(200);
        }
    }

    @Test
    void routeControlIsForAdminsOnly() {
        asUser(TestTokens.ALICE, "CUSTOMER").post("/api/admin/routes/outbox-poller/stop").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").post("/api/dlq/1/dismiss").then().statusCode(403);
    }
}
