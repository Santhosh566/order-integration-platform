package com.example.orders;

import com.example.orders.service.DeliveryCodeGenerator;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doReturn;

/**
 * Cancelling some items before dispatch. CUST-100 (Alice) lives in Karnataka, the seller's state,
 * so a ₹1,180 unit is ₹1,000 taxable value with ₹90 CGST and ₹90 SGST. Orders wait at the dock
 * for a driver: nothing delivers them by itself here.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class PartialCancelIT extends IntegrationTestBase {

    private static final String CODE = "123456";

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixedCode() {
        doReturn(CODE).when(codeGenerator).next();
    }

    @Test
    void cancellingOneUnit_refundsIt_withACreditNote_andTheRestIsDelivered() {
        String kettle = product(1180.00, 10);
        String mug = product(590.00, 10);
        String orderId = place(alice(), Map.of("items", List.of(line(kettle, 3), line(mug, 1))));
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        BigDecimal revenueBefore = revenue();

        cancelItems(alice(), orderId, List.of(line(kettle, 1)), "Ordered one too many").statusCode(200)
            .body("wholeOrderCancelled", equalTo(false))
            .body("refunded", equalTo(1180.0f))
            .body("creditNote.kind", equalTo("CANCELLATION"))
            .body("creditNote.number", matchesPattern("CN/\\d{2}-\\d{2}/\\d{6}"))
            .body("creditNote.total", equalTo(1180.0f))
            .body("creditNote.taxableValue", equalTo(1000.0f))
            .body("creditNote.cgstAmount", equalTo(90.0f))
            .body("creditNote.reason", equalTo("Ordered one too many"))
            .body("order.status", equalTo("READY_FOR_DISPATCH"))
            .body("order.amountDue", equalTo(2950.0f))
            .body("order.items.find { it.sku == '" + kettle + "' }.activeQuantity", equalTo(2))
            .body("order.items.find { it.sku == '" + kettle + "' }.cancelledQuantity", equalTo(1))
            .body("order.fraudScore", nullValue());

        Order order = order(orderId);
        // The invoice keeps what was ordered; the credit note takes the unit off it
        assertThat(order.getInvoiceNumber()).isNotNull();
        assertThat(order.getTotal()).isEqualByComparingTo("4130.00");
        assertThat(order.getCancelledTotal()).isEqualByComparingTo("1180.00");
        assertThat(order.getRefundedTotal()).isEqualByComparingTo("1180.00");
        assertThat(order.getRefundedAt()).isNull();
        assertThat(refunds(orderId)).containsExactly(new BigDecimal("1180.00"));
        assertThat(revenue()).isEqualByComparingTo(revenueBefore.subtract(new BigDecimal("1180")));
        assertStock(kettle, 8, 2);
        assertThat(jdbc.queryForObject("select count(*) from customer_notifications where order_id = ? "
                + "and subject like 'Items cancelled from order%'", Integer.class, orderId)).isEqualTo(1);

        // Only two are left to cancel
        cancelItems(alice(), orderId, List.of(line(kettle, 3)), null).statusCode(400)
            .body("reason", containsString("Only 2 of " + kettle));

        // The driver's parcel holds only what is left, and only that leaves the warehouse
        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take").then()
            .statusCode(200)
            .body("items.find { it.sku == '" + kettle + "' }.quantity", equalTo(2));
        deliver(orderId, Map.of());
        assertStock(kettle, 8, 0);
        assertStock(mug, 9, 0);

        // A return can take back only the units delivered
        alice().contentType(ContentType.JSON)
            .body(Map.of("orderId", orderId, "reason", "Not needed", "items", List.of(line(kettle, 3))))
            .post("/api/returns").then().statusCode(400).body("reason", containsString("Only 2"));
    }

    @Test
    void cancellingWhatIsLeft_cancelsTheWholeOrder_andRefundsTheRest() {
        String lamp = product(1180.00, 10);
        String orderId = place(alice(), Map.of("items", List.of(line(lamp, 2))));
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);

        cancelItems(alice(), orderId, List.of(line(lamp, 1)), null).statusCode(200)
            .body("creditNote.reason", equalTo("Cancelled by the customer before dispatch"));
        cancelItems(alice(), orderId, List.of(line(lamp, 1)), null).statusCode(200)
            .body("wholeOrderCancelled", equalTo(true))
            .body("creditNote", nullValue())
            .body("order.status", equalTo("CANCELLED"));

        Order order = order(orderId);
        assertThat(order.getRefundedTotal()).isEqualByComparingTo(order.getTotal());
        assertThat(order.getRefundedAt()).isNotNull();
        assertThat(refunds(orderId)).containsExactly(new BigDecimal("1180.00"), new BigDecimal("1180.00"));
        assertStock(lamp, 10, 0);
    }

    @Test
    void onCashOnDelivery_theDriverCollectsLess_andNothingIsRefunded() {
        String fan = product(1180.00, 10);
        String orderId = place(alice(), Map.of("items", List.of(line(fan, 3)), "paymentMethod", "COD"));
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);

        cancelItems(alice(), orderId, List.of(line(fan, 1)), null).statusCode(200)
            .body("refunded", nullValue())
            .body("creditNote.total", equalTo(1180.0f))
            .body("creditNote.paymentId", nullValue())
            .body("order.amountDue", equalTo(2360.0f));
        assertThat(refunds(orderId)).isEmpty();

        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take").then()
            .statusCode(200).body("amountToCollect", equalTo(2360.0f));
        deliver(orderId, Map.of("cashCollected", true));

        assertThat(jdbc.queryForList("select kind || ' ' || amount from payment_transactions where order_id = ?",
            String.class, orderId)).containsExactly("CHARGE 2360.00");
        // Everything collected can still be refunded, and no more
        assertThat(order(orderId).refundableAmount()).isEqualByComparingTo("2360.00");
    }

    @Test
    void theRules() {
        String sku = product(500.00, 10);
        String orderId = place(alice(), Map.of("items", List.of(line(sku, 2))));
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);

        // Someone else's order looks like a missing one
        cancelItems(asUser(TestTokens.BOB, "CUSTOMER"), orderId, List.of(line(sku, 1)), null).statusCode(404);
        cancelItems(alice(), orderId, List.of(), null).statusCode(400);
        cancelItems(alice(), orderId, List.of(line("NO-SUCH-SKU", 1)), null).statusCode(400);
        cancelItems(alice(), orderId, List.of(line(sku, 0)), null).statusCode(400);

        // An admin can cancel too, and the credit note says so
        cancelItems(given(), orderId, List.of(line(sku, 1)), null).statusCode(200)
            .body("creditNote.reason", equalTo("Cancelled by staff before dispatch"))
            .body("order.orderType", org.hamcrest.Matchers.notNullValue());

        // Once the driver has it, it is too late
        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take").then().statusCode(200);
        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/pickup").then().statusCode(200);
        cancelItems(alice(), orderId, List.of(line(sku, 1)), null).statusCode(409)
            .body("reason", containsString("before it leaves the warehouse"));
        assertThat(order(orderId).getItems()).extracting(OrderItem::getActiveQuantity).containsExactly(1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification driver() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }

    private String product(double price, int stock) {
        String sku = newSku(stock);
        setPrice(sku, price);
        return sku;
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private String place(RequestSpecification who, Map<String, Object> body) {
        return who.contentType(ContentType.JSON).body(body).post("/api/orders").then().statusCode(202)
            .extract().path("orderId");
    }

    private ValidatableResponse cancelItems(RequestSpecification who, String orderId, List<Map<String, Object>> items,
                                            String reason) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        body.put("reason", reason);
        return who.contentType(ContentType.JSON).body(body).post("/api/orders/" + orderId + "/cancel-items").then();
    }

    private void deliver(String orderId, Map<String, Object> extra) {
        String base = "/api/delivery/orders/" + orderId + "/";
        driver().contentType(ContentType.JSON).body(Map.of()).post(base + "pickup").then().statusCode(200);
        driver().contentType(ContentType.JSON).body(Map.of()).post(base + "out-for-delivery").then().statusCode(200);
        Map<String, Object> body = new java.util.HashMap<>(extra);
        body.put("code", CODE);
        driver().contentType(ContentType.JSON).body(body).post(base + "deliver").then().statusCode(200);
        awaitStatus(orderId, OrderStatus.DELIVERED);
    }

    private List<BigDecimal> refunds(String orderId) {
        return jdbc.queryForList("select amount from payment_transactions where order_id = ? and kind = 'REFUND' "
                + "order by payment_id", BigDecimal.class, orderId);
    }

    private BigDecimal revenue() {
        return given().get("/api/admin/metrics").then().statusCode(200)
            .extract().jsonPath(JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .get("totalRevenue");
    }
}
