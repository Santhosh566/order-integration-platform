package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Returns and partial refunds. CUST-100 (Alice) lives in Karnataka, the seller's state, so each
 * ₹1,180 unit carries ₹90 CGST and ₹90 SGST on ₹1,000 taxable value.
 */
class ReturnsIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void returningPartOfAnOrder_refundsThoseUnits_withACreditNote_andRestocksThem() {
        String sku = newSku(10);
        setPrice(sku, 1180.00);
        String orderId = deliveredOrder(sku, 3);
        assertStock(sku, 7, 0);

        String returnId = requestReturn(orderId, sku, 2).statusCode(201)
            .body("status", equalTo("REQUESTED")).extract().path("returnId");
        given().contentType(ContentType.JSON).body(Map.of("note", "OK")).post("/api/admin/returns/" + returnId + "/approve")
            .then().statusCode(200).body("status", equalTo("APPROVED"));
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/receive")
            .then().statusCode(200)
            .body("number", matchesPattern("CN/\\d{2}-\\d{2}/\\d{6}"))
            .body("total", equalTo(2360.0f))
            .body("taxableValue", equalTo(2000.0f))
            .body("cgstAmount", equalTo(180.0f))
            .body("sgstAmount", equalTo(180.0f));

        Order order = order(orderId);
        assertThat(order.getRefundedTotal()).isEqualByComparingTo("2360.00");
        assertThat(order.getReturnStatus()).isEqualTo(Order.ReturnStatus.PARTIAL);
        assertThat(order.getRefundedAt()).as("not everything was refunded").isNull();
        assertThat(refunds(orderId)).containsExactly(new BigDecimal("2360.00"));
        assertStock(sku, 9, 0);
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_notifications where order_id = ? "
                + "and subject like 'Refund for your return%'", Integer.class, orderId)).isEqualTo(1);

        // Only one unit is left to return
        requestReturn(orderId, sku, 2).statusCode(400).body("reason", containsString("Only 1"));
        String last = requestReturn(orderId, sku, 1).statusCode(201).extract().path("returnId");
        approveAndReceive(last, Map.of()).body("total", equalTo(1180.0f));

        order = order(orderId);
        assertThat(order.getRefundedTotal()).isEqualByComparingTo(order.getTotal());
        assertThat(order.getReturnStatus()).isEqualTo(Order.ReturnStatus.FULL);
        assertThat(order.getRefundedAt()).isNotNull();
        assertStock(sku, 10, 0);
    }

    @Test
    void damagedGoods_areRefunded_butNotPutBackOnTheShelf() {
        String sku = newSku(10);
        setPrice(sku, 500.00);
        String orderId = deliveredOrder(sku, 2);

        String returnId = requestReturn(orderId, sku, 2).statusCode(201).extract().path("returnId");
        approveAndReceive(returnId, Map.of("conditions", Map.of(sku, "DAMAGED"))).body("total", equalTo(1000.0f));

        assertStock(sku, 8, 0);
        given().get("/api/returns/" + returnId).then().body("items[0].condition", equalTo("DAMAGED"))
            .body("status", equalTo("REFUNDED"));
    }

    @Test
    void returningUnitsOneByOne_refundsExactlyWhatWasPaid_toThePaisa() {
        String sku = newSku(10);
        setPrice(sku, 33.33);
        String orderId = deliveredOrder(sku, 3);
        Order order = order(orderId);

        BigDecimal refunded = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            String returnId = requestReturn(orderId, sku, 1).statusCode(201).extract().path("returnId");
            refunded = refunded.add(approveAndReceive(returnId, Map.of()).extract()
                .jsonPath(JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
                .get("total"));
        }

        assertThat(refunded).isEqualByComparingTo(order.getTotal());
        Map<String, Object> tax = jdbcTemplate.queryForMap("select sum(taxable_value) taxable, sum(cgst_amount) cgst, "
                + "sum(sgst_amount) sgst from credit_notes where order_id = ?", orderId);
        assertThat((BigDecimal) tax.get("taxable")).isEqualByComparingTo(order.getSubtotal());
        assertThat((BigDecimal) tax.get("cgst")).isEqualByComparingTo(order.getCgstTotal());
        assertThat((BigDecimal) tax.get("sgst")).isEqualByComparingTo(order.getSgstTotal());
    }

    @Test
    void aGoodwillRefund_lowersWhatALaterReturnGivesBack_andRevenue() {
        String sku = newSku(10);
        setPrice(sku, 1180.00);
        String orderId = deliveredOrder(sku, 2);
        BigDecimal revenueBefore = revenue();

        given().contentType(ContentType.JSON).body(Map.of("amount", 200, "reason", "Scratched on arrival"))
            .post("/api/admin/orders/" + orderId + "/refunds").then().statusCode(200)
            .body("kind", equalTo("GOODWILL"))
            .body("total", equalTo(200.0f))
            .body("cgstAmount", equalTo(15.25f))
            .body("sgstAmount", equalTo(15.25f))
            .body("taxableValue", equalTo(169.5f));
        assertThat(revenue()).isEqualByComparingTo(revenueBefore.subtract(new BigDecimal("200")));

        given().contentType(ContentType.JSON).body(Map.of("amount", 5000, "reason", "Too much"))
            .post("/api/admin/orders/" + orderId + "/refunds").then().statusCode(409);

        // Returning everything now gives back only what is left
        String returnId = requestReturn(orderId, sku, 2).statusCode(201).extract().path("returnId");
        approveAndReceive(returnId, Map.of()).body("total", equalTo(2160.0f));
        assertThat(order(orderId).getRefundedTotal()).isEqualByComparingTo("2360.00");
        assertThat(refunds(orderId)).containsExactly(new BigDecimal("200.00"), new BigDecimal("2160.00"));
    }

    @Test
    void returnsFollowTheRules() {
        String sku = newSku(10);
        String orderId = deliveredOrder(sku, 3);

        // Someone else's order looks like a missing one
        asUser(TestTokens.BOB, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("orderId", orderId, "reason", "Mine now", "items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/returns").then().statusCode(404);

        // A rejection needs a reason, and frees the units for another request
        String rejected = requestReturn(orderId, sku, 3).statusCode(201).extract().path("returnId");
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + rejected + "/reject")
            .then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("note", "Used item"))
            .post("/api/admin/returns/" + rejected + "/reject").then().statusCode(200).body("status", equalTo("REJECTED"));
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + rejected + "/receive")
            .then().statusCode(409);
        requestReturn(orderId, sku, 3).statusCode(201);

        // Customers cannot decide on returns
        asUser(TestTokens.ALICE, "CUSTOMER").post("/api/admin/returns/" + rejected + "/approve").then().statusCode(403);

        // The return window closes 7 days after delivery
        String old = deliveredOrder(sku, 1);
        jdbcTemplate.update("update orders set delivered_at = now() - interval '8 days' where order_id = ?", old);
        requestReturn(old, sku, 1).statusCode(409).body("reason", containsString("window"));

        // Only delivered orders can be returned
        String unsent = placeOrder(newSku(0), 1, 10.0);
        awaitStatus(unsent, OrderStatus.OUT_OF_STOCK);
        requestReturn(unsent, sku, 1).statusCode(409);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private String deliveredOrder(String sku, int quantity) {
        String orderId = alice().contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", quantity))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.DELIVERED);
        return orderId;
    }

    private ValidatableResponse requestReturn(String orderId, String sku, int quantity) {
        return alice().contentType(ContentType.JSON)
            .body(Map.of("orderId", orderId, "reason", "Does not fit", "items", List.of(Map.of("sku", sku, "quantity", quantity))))
            .post("/api/returns").then();
    }

    private ValidatableResponse approveAndReceive(String returnId, Map<String, Object> inspection) {
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/approve")
            .then().statusCode(200);
        return given().contentType(ContentType.JSON).body(inspection).post("/api/admin/returns/" + returnId + "/receive")
            .then().statusCode(200);
    }

    private List<BigDecimal> refunds(String orderId) {
        return jdbcTemplate.queryForList("select amount from payment_transactions where order_id = ? and kind = 'REFUND' "
                + "order by payment_id", BigDecimal.class, orderId);
    }

    private BigDecimal revenue() {
        return given().get("/api/admin/metrics").then().statusCode(200)
            .extract().jsonPath(JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .get("totalRevenue");
    }
}
