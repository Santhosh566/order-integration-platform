package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Checkout extras: coupons (GST on the price after the discount), cash on delivery, and booked delivery
 * slots. Alice lives in Karnataka, the seller's state, so GST is CGST + SGST.
 */
@TestPropertySource(properties = {
        // Small enough to fill in a test
        "orders.checkout.slot-capacity=2",
        "orders.checkout.cod-max-order-value=5000"
})
class CheckoutIT extends IntegrationTestBase {

    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.97,77.64
        """;

    @Autowired
    JdbcTemplate jdbc;

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification bob() {
        return asUser(TestTokens.BOB, "CUSTOMER");
    }

    private String product(double price) {
        String sku = newSku(100);
        setPrice(sku, price);
        return sku;
    }

    private String coupon(Map<String, Object> fields) {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "description", "Test offer"));
        body.putAll(fields);
        given().contentType(ContentType.JSON).body(body).post("/api/admin/coupons").then().statusCode(201)
            .body("code", equalTo(code));
        return code;
    }

    private static Map<String, Object> order(List<Map<String, Object>> items, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("items", items);
        return body;
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private JsonPath quote(RequestSpecification who, Map<String, Object> body) {
        return who.contentType(ContentType.JSON).body(body).post("/api/checkout/quote").then().statusCode(200)
            .extract().jsonPath(JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    }

    private String place(RequestSpecification who, Map<String, Object> body) {
        return who.contentType(ContentType.JSON).body(body).post("/api/orders").then().statusCode(202)
            .extract().path("orderId");
    }

    @Test
    void aCouponComesOffBeforeGst_sharedAcrossTheLines() {
        String kettle = product(1180.00);
        String mug = product(590.00);
        // 10% of ₹2,950 is ₹295, capped at ₹100
        String code = coupon(Map.of("discountType", "PERCENT", "discountValue", 10, "maxDiscount", 100));
        Map<String, Object> body = order(List.of(line(kettle, 2), line(mug, 1)), Map.of("couponCode", code.toLowerCase()));

        JsonPath quote = quote(alice(), body);
        assertThat(quote.getString("couponCode")).isEqualTo(code);
        assertThat(quote.getString("couponError")).isNull();
        assertThat(quote.<BigDecimal>get("itemsTotal")).isEqualByComparingTo("2950.00");
        assertThat(quote.<BigDecimal>get("discount")).isEqualByComparingTo("100.00");
        assertThat(quote.<BigDecimal>get("total")).isEqualByComparingTo("2850.00");
        // By value: the mug takes ₹20, the kettles the rest
        assertThat(quote.<BigDecimal>get("items.find { it.sku == '" + mug + "' }.discount")).isEqualByComparingTo("20.00");
        assertThat(quote.<BigDecimal>get("items.find { it.sku == '" + kettle + "' }.discount")).isEqualByComparingTo("80.00");

        String orderId = place(alice(), body);
        Order placed = order(orderId);
        assertThat(placed.getCouponCode()).isEqualTo(code);
        assertThat(placed.getDiscountTotal()).isEqualByComparingTo("100.00");
        assertThat(placed.getTotal()).isEqualByComparingTo("2850.00");
        // GST on ₹2,280 for the kettles: taxable 1,932.20, 18% = 347.80 split evenly
        var kettles = placed.getItems().stream().filter(i -> i.getSku().equals(kettle)).findFirst().orElseThrow();
        assertThat(kettles.getLineTotal()).isEqualByComparingTo("2280.00");
        assertThat(kettles.getTaxableValue()).isEqualByComparingTo("1932.20");
        assertThat(kettles.getCgstAmount()).isEqualByComparingTo("173.90");
        assertThat(placed.getTaxTotal()).isEqualByComparingTo(
            placed.getItems().stream().map(i -> i.taxAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
        awaitStatus(orderId, OrderStatus.DELIVERED);

        // Returning the mug refunds what was paid for it, ₹570, not its list price
        String returnId = alice().contentType(ContentType.JSON)
            .body(Map.of("orderId", orderId, "reason", "Chipped", "items", List.of(line(mug, 1))))
            .post("/api/returns").then().statusCode(201).extract().path("returnId");
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/approve").then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/returns/" + returnId + "/receive").then().statusCode(200)
            .body("total", equalTo(570.00f));
    }

    @Test
    void couponRules_areExplainedInTheQuote_andEnforcedOnTheOrder() {
        String lamp = product(1000.00);

        JsonPath unknown = quote(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", "NOSUCHCODE")));
        assertThat(unknown.getString("couponError")).isEqualTo("Coupon NOSUCHCODE is not valid");
        assertThat(unknown.<BigDecimal>get("total")).isEqualByComparingTo("1000.00");
        assertThat(unknown.getString("couponCode")).isNull();

        String big = coupon(Map.of("discountType", "FLAT", "discountValue", 300, "minOrderValue", 2500));
        assertThat(quote(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", big))).getString("couponError"))
            .contains("needs an order of at least").contains("add ₹1,500.00 more");
        assertThat(quote(alice(), order(List.of(line(lamp, 3)), Map.of("couponCode", big))).<BigDecimal>get("total"))
            .isEqualByComparingTo("2700.00");
        // Placing the order refuses a coupon that does not apply
        alice().contentType(ContentType.JSON).body(order(List.of(line(lamp, 1)), Map.of("couponCode", big)))
            .post("/api/orders").then().statusCode(400).body("reason", containsString("needs an order of at least"));

        // Once per customer; a cancelled order gives its use back
        String once = coupon(Map.of("discountType", "FLAT", "discountValue", 50));
        String first = place(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", once)));
        assertThat(quote(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", once))).getString("couponError"))
            .isEqualTo("You have already used coupon " + once);
        assertThat(alice().get("/api/checkout/offers").then().statusCode(200).extract().jsonPath().getList("code"))
            .doesNotContain(once);
        // Bob uses it too; his order is then cancelled, which gives the use back
        String second = bob().contentType(ContentType.JSON).body(order(List.of(line(lamp, 1)), Map.of("couponCode", once)))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        jdbc.update("update orders set status = 'CANCELLED' where order_id = ?", second);
        assertThat(quote(bob(), order(List.of(line(lamp, 1)), Map.of("couponCode", once))).getString("couponError")).isNull();

        // First order only: Alice has ordered before
        String welcome = coupon(Map.of("discountType", "PERCENT", "discountValue", 10, "firstOrderOnly", true));
        assertThat(quote(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", welcome))).getString("couponError"))
            .isEqualTo("Coupon " + welcome + " is for your first order only");

        // A total limit
        String single = coupon(Map.of("discountType", "FLAT", "discountValue", 10, "usageLimit", 1));
        place(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", single)));
        assertThat(quote(bob(), order(List.of(line(lamp, 1)), Map.of("couponCode", single))).getString("couponError"))
            .isEqualTo("Coupon " + single + " has been fully used");

        // Switched off by an admin
        String paused = coupon(Map.of("discountType", "FLAT", "discountValue", 10));
        given().contentType(ContentType.JSON).body(Map.of("active", false)).patch("/api/admin/coupons/" + paused)
            .then().statusCode(200).body("active", equalTo(false));
        assertThat(quote(alice(), order(List.of(line(lamp, 1)), Map.of("couponCode", paused))).getString("couponError"))
            .isEqualTo("Coupon " + paused + " is not valid");

        // Mistakes in a new coupon are refused
        given().contentType(ContentType.JSON).body(Map.of("code", "x", "description", "d", "discountType", "FLAT", "discountValue", 5))
            .post("/api/admin/coupons").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("code", "TOOMUCH", "description", "d", "discountType", "PERCENT", "discountValue", 95))
            .post("/api/admin/coupons").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("code", once, "description", "d", "discountType", "FLAT", "discountValue", 5))
            .post("/api/admin/coupons").then().statusCode(400).body("reason", containsString("already a coupon"));
        alice().get("/api/admin/coupons").then().statusCode(403);
        given().get("/api/checkout/offers").then().statusCode(403);
    }

    @Test
    void clothingIsTaxedOnItsPriceAfterTheDiscount() {
        long category = given().contentType(ContentType.JSON)
            .body(Map.of("name", "Test kurtas " + UUID.randomUUID().toString().substring(0, 6), "gstRule", "APPAREL_2500"))
            .post("/api/admin/categories").then().statusCode(201).extract().jsonPath().getLong("id");
        String kurta = product(2700.00);
        var item = inventoryRepository.findById(kurta).orElseThrow();
        item.setCategoryId(category);
        item.setGstRate(new BigDecimal("18.00"));
        inventoryRepository.save(item);

        // ₹2,700 is above ₹2,625 (₹2,500 at 5%), so 18%; ₹200 off makes it ₹2,500 a piece, so 5%
        assertThat(quote(alice(), order(List.of(line(kurta, 1)), Map.of())).<BigDecimal>get("items[0].gstRate"))
            .isEqualByComparingTo("18");
        String code = coupon(Map.of("discountType", "FLAT", "discountValue", 200));
        JsonPath discounted = quote(alice(), order(List.of(line(kurta, 1)), Map.of("couponCode", code)));
        assertThat(discounted.<BigDecimal>get("items[0].gstRate")).isEqualByComparingTo("5");
        assertThat(discounted.<BigDecimal>get("total")).isEqualByComparingTo("2500.00");
        assertThat(discounted.<BigDecimal>get("taxTotal")).isEqualByComparingTo("119.04");
    }

    @Test
    void cashOnDelivery_isChargedOnlyWhenTheDriverCollectsIt() {
        String fan = product(1500.00);
        String orderId = place(alice(), order(List.of(line(fan, 1)), Map.of("paymentMethod", "COD")));
        awaitStatus(orderId, OrderStatus.DELIVERED);
        Order delivered = order(orderId);
        assertThat(delivered.getPaymentMethod()).isEqualTo(Order.PaymentMethod.COD);
        assertThat(delivered.getPaymentGateway()).isEqualTo("CASH_ON_DELIVERY");
        assertThat(jdbc.queryForList("select kind || ' ' || gateway || ' ' || amount from payment_transactions where order_id = ?",
            String.class, orderId)).containsExactly("CHARGE CASH_ON_DELIVERY 1500.00");

        // Over the COD limit (₹5,000 here): the quote says so, and the order is refused
        JsonPath over = quote(alice(), order(List.of(line(fan, 4)), Map.of("paymentMethod", "COD")));
        assertThat(over.getBoolean("cashOnDelivery.available")).isFalse();
        assertThat(over.getString("cashOnDelivery.reason")).contains("orders up to ₹5,000.00");
        alice().contentType(ContentType.JSON).body(order(List.of(line(fan, 4)), Map.of("paymentMethod", "COD")))
            .post("/api/orders").then().statusCode(400).body("reason", containsString("Cash on delivery is for orders up to"));
    }

    @Test
    void cancellingACashOnDeliveryOrder_refundsNothing() {
        // Held at the dock: the release and delivery timer is paused
        given().post("/api/admin/routes/carrier-tracking-poller/suspend").then().statusCode(200);
        try {
            String fan = product(800.00);
            String orderId = place(alice(), order(List.of(line(fan, 1)), Map.of("paymentMethod", "COD")));
            awaitStatus(orderId, OrderStatus.SHIPPED);
            alice().contentType(ContentType.JSON).body(Map.of("status", "CANCELLED")).patch("/api/orders/" + orderId + "/status")
                .then().statusCode(200);
            assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order(orderId).getRefundedTotal()).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_id = ?", Integer.class, orderId)).isZero();
        } finally {
            given().post("/api/admin/routes/carrier-tracking-poller/resume").then().statusCode(200);
        }
    }

    @Test
    void aDeliverySlotCanBeBooked_untilItIsFull() {
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200);
        String lamp = product(300.00);

        JsonPath slots = alice().get("/api/checkout/slots?pin=560038").then().statusCode(200).extract().jsonPath();
        assertThat(slots.getBoolean("bookable")).isTrue();
        List<String> days = slots.getList("days.date");
        assertThat(days).hasSize(6);
        assertThat(days.get(0)).isGreaterThanOrEqualTo(slots.getString("standardBy"));
        assertThat(slots.getList("days[0].windows.window")).containsExactly("MORNING", "AFTERNOON", "EVENING");
        assertThat(slots.getString("days[0].windows[0].label")).isEqualTo("9 AM – 1 PM");

        // Two places in the evening of the last day
        String day = days.get(days.size() - 1);
        Map<String, Object> evening = Map.of("deliverySlot", Map.of("date", day, "window", "EVENING"));
        String first = place(alice(), order(List.of(line(lamp, 1)), evening));
        assertThat(order(first).getDeliveryDate()).hasToString(day);
        assertThat(order(first).getDeliveryWindow()).isEqualTo(Order.DeliveryWindow.EVENING);
        place(alice(), order(List.of(line(lamp, 1)), evening));
        alice().contentType(ContentType.JSON).body(order(List.of(line(lamp, 1)), evening)).post("/api/orders").then()
            .statusCode(400).body("reason", containsString("has just filled up"));
        assertThat(alice().get("/api/checkout/slots?pin=560038").then().extract().jsonPath()
            .getBoolean("days.find { it.date == '" + day + "' }.windows.find { it.window == 'EVENING' }.available")).isFalse();

        // A cancelled order frees its place
        jdbc.update("update orders set status = 'CANCELLED' where order_id = ?", first);
        place(alice(), order(List.of(line(lamp, 1)), evening));

        // Only the days offered can be booked, and a slot needs both parts
        alice().contentType(ContentType.JSON)
            .body(order(List.of(line(lamp, 1)), Map.of("deliverySlot", Map.of("date", "2020-01-01", "window", "MORNING"))))
            .post("/api/orders").then().statusCode(400).body("reason", containsString("can no longer be booked"));
        alice().contentType(ContentType.JSON)
            .body(order(List.of(line(lamp, 1)), Map.of("deliverySlot", Map.of("date", day))))
            .post("/api/orders").then().statusCode(400).body("reason", containsString("both a day and a time"));

        assertThat(alice().get("/api/checkout/slots?pin=12").then().statusCode(200).extract().jsonPath()
            .getBoolean("bookable")).isFalse();
    }

}
