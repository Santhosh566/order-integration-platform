package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.service.CartReminderService;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@TestPropertySource(properties = {
        // No quiet hours, so the tests run at any time of day; the timer stays out of the way
        "orders.cart.quiet-from=00:00",
        "orders.cart.quiet-until=00:00",
        "orders.cart.reminder-poll-ms=3600000"
})
class AbandonedCartIT extends IntegrationTestBase {

    private static final String ALICE_ID = "CUST-100";

    @Autowired JdbcTemplate jdbc;
    @Autowired CartReminderService reminders;

    @BeforeEach
    void startWithoutCarts() {
        // Carts other tests left open would be reminded about, and earlier coupons would stop a new one
        jdbc.update("update carts set status = 'EMPTIED', closed_at = now() where status = 'ACTIVE'");
        jdbc.update("update coupons set created_at = now() - interval '60 days' where created_by = 'cart-reminder'");
    }

    @Test
    void theCartIsKeptOnTheServer_mergingKeepsTheLargerQuantity_andProductsNoLongerSoldAreLeftOut() {
        String pen = product(50.00, 100);
        String mug = product(250.00, 100);
        String gone = product(99.00, 100);
        jdbc.update("update inventory set active = false where sku = ?", gone);

        alice().contentType(ContentType.JSON).body(List.of(line(pen, 2), line(gone, 1))).put("/api/cart").then().statusCode(200)
            .body("lines", hasSize(1)).body("lines[0].sku", equalTo(pen)).body("itemCount", equalTo(2));

        // Another device, or the cart this browser kept before: the larger quantity wins
        JsonPath merged = json(alice().contentType(ContentType.JSON).body(List.of(line(pen, 1), line(mug, 3)))
            .post("/api/cart/merge").then().statusCode(200).extract().jsonPath());
        assertThat(merged.getInt("itemCount")).isEqualTo(5);
        assertThat(merged.<BigDecimal>get("total")).isEqualByComparingTo("850.00");
        assertThat(merged.getInt("lines.find { it.sku == '" + pen + "' }.quantity")).isEqualTo(2);

        // Cheaper since it was put in
        setPrice(mug, 200.00);
        alice().get("/api/cart").then().statusCode(200)
            .body("lines.find { it.sku == '" + mug + "' }.priceDropped", equalTo(true));

        // Alice accepts today's price; a made-up one is ignored
        alice().contentType(ContentType.JSON).body(List.of(
                Map.of("sku", pen, "quantity", 2, "priceSeen", 1.00),
                Map.of("sku", mug, "quantity", 3, "priceSeen", 200.00)))
            .put("/api/cart").then().statusCode(200)
            .body("lines.find { it.sku == '" + mug + "' }.priceDropped", equalTo(false))
            .body("lines.find { it.sku == '" + mug + "' }.priceSeen", equalTo(200.0f))
            .body("lines.find { it.sku == '" + pen + "' }.priceSeen", equalTo(50.0f));

        // Each customer has their own; staff have none
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/cart").then().statusCode(200).body("lines", hasSize(0));
        given().get("/api/cart").then().statusCode(403);

        alice().delete("/api/cart").then().statusCode(204);
        alice().get("/api/cart").then().statusCode(200).body("lines", hasSize(0));
        assertThat(jdbc.queryForObject("select count(*) from carts where customer_id = ? and status = 'EMPTIED'",
            Integer.class, ALICE_ID)).isPositive();
    }

    @Test
    void aLeftCartIsRemindedAfterAnHour_thenWithAPersonalCoupon_andTheOrderRecoversIt() {
        String kettle = product(1000.00, 50);
        String mug = product(200.00, 50);
        alice().contentType(ContentType.JSON).body(List.of(line(kettle, 1), line(mug, 2))).put("/api/cart").then().statusCode(200);
        long cartId = activeCart();

        // Too soon
        reminders.remindDue();
        assertThat(remindersSent(cartId)).isZero();

        // An hour later: the first reminder, without a coupon
        age(cartId, "61 minutes");
        reminders.remindDue();
        assertThat(remindersSent(cartId)).isEqualTo(1);
        Map<String, Object> first = lastEmail();
        assertThat(first.get("subject")).isEqualTo("You left 3 items in your cart");
        assertThat((String) first.get("text")).contains("1 × Test product — ₹1,000.00").contains("/cart\n")
            .contains("turn off cart reminders");

        // Not again until the next reminder is due
        reminders.remindDue();
        assertThat(remindersSent(cartId)).isEqualTo(1);

        // A day later: the last reminder carries a 5% coupon for Alice only
        age(cartId, "25 hours");
        reminders.remindDue();
        assertThat(remindersSent(cartId)).isEqualTo(2);
        String code = jdbc.queryForObject("select reminder_coupon from carts where cart_id = ?", String.class, cartId);
        assertThat(code).startsWith("CART");
        Map<String, Object> second = lastEmail();
        assertThat(second.get("subject")).isEqualTo("5% off your cart, for the next 48 hours");
        assertThat((String) second.get("text")).contains("Use code " + code).contains("/cart?coupon=" + code);

        // Bob cannot use it, and it is in nobody's offers but Alice's
        Map<String, Object> order = Map.of("items", List.of(line(kettle, 1)), "couponCode", code);
        asUser(TestTokens.BOB, "CUSTOMER").contentType(ContentType.JSON).body(order).post("/api/checkout/quote").then()
            .statusCode(200).body("couponError", containsString("is not valid"));
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/checkout/offers").then().statusCode(200)
            .body("code", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(code)));
        alice().get("/api/checkout/offers").then().statusCode(200).body("code", org.hamcrest.Matchers.hasItem(code));
        given().get("/api/admin/coupons").then().statusCode(200)
            .body("code", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(code)));

        // Alice orders the kettle with it: the cart is recovered, the mug waits in a new cart
        String orderId = alice().contentType(ContentType.JSON).body(order).post("/api/orders").then().statusCode(202)
            .extract().path("orderId");
        Order placed = order(orderId);
        assertThat(placed.getDiscountTotal()).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForMap("select status, order_id from carts where cart_id = ?", cartId))
            .containsEntry("status", "RECOVERED").containsEntry("order_id", orderId);
        alice().get("/api/cart").then().statusCode(200).body("lines", hasSize(1)).body("lines[0].sku", equalTo(mug));

        // The new cart is reminded too, but no second coupon within a month
        long next = activeCart();
        age(next, "61 minutes");
        reminders.remindDue();
        age(next, "25 hours");
        reminders.remindDue();
        assertThat(remindersSent(next)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reminder_coupon from carts where cart_id = ?", String.class, next)).isNull();

        JsonPath stats = json(given().get("/api/admin/carts/stats").then().statusCode(200).extract().jsonPath());
        assertThat(stats.getInt("recovered")).isPositive();
        assertThat(stats.getInt("couponsUsed")).isPositive();
        assertThat(stats.<BigDecimal>get("recoveredRevenue")).isGreaterThanOrEqualTo(placed.getTotal());
        given().get("/api/admin/carts?status=RECOVERED").then().statusCode(200)
            .body("items.find { it.cartId == " + cartId + " }.orderId", equalTo(orderId));
    }

    @Test
    void anOrderWithoutAReminderClosesTheCart_andWhatWasNotOrderedStays() {
        String pen = product(50.00, 100);
        String mug = product(250.00, 100);
        alice().contentType(ContentType.JSON).body(List.of(line(pen, 2), line(mug, 1))).put("/api/cart").then().statusCode(200);
        long cartId = activeCart();

        placeAsAlice(pen, 2);
        assertThat(activeCart()).isEqualTo(cartId);
        alice().get("/api/cart").then().statusCode(200).body("lines", hasSize(1)).body("lines[0].sku", equalTo(mug));

        String orderId = placeAsAlice(mug, 1);
        assertThat(jdbc.queryForMap("select status, order_id from carts where cart_id = ?", cartId))
            .containsEntry("status", "ORDERED").containsEntry("order_id", orderId);
    }

    @Test
    void noReminder_whenNothingCanBeBought_orTheCustomerTurnedThemOff() {
        String soldOut = product(500.00, 1);
        alice().contentType(ContentType.JSON).body(List.of(line(soldOut, 1))).put("/api/cart").then().statusCode(200);
        long cartId = activeCart();
        jdbc.update("update inventory set available_quantity = 0 where sku = ?", soldOut);
        age(cartId, "2 hours");
        reminders.remindDue();
        assertThat(remindersSent(cartId)).isZero();
        assertThat(jdbc.queryForObject("select checked_at is not null from carts where cart_id = ?", Boolean.class, cartId)).isTrue();

        // Back in stock, but Alice turned reminders off in her profile
        jdbc.update("update inventory set available_quantity = 5 where sku = ?", soldOut);
        jdbc.update("update carts set checked_at = null where cart_id = ?", cartId);
        setCartReminders(false);
        try {
            alice().get("/api/me/profile").then().statusCode(200).body("customer.cartReminders", equalTo(false));
            reminders.remindDue();
            assertThat(remindersSent(cartId)).isZero();
        } finally {
            setCartReminders(true);
        }
        reminders.remindDue();
        assertThat(remindersSent(cartId)).isEqualTo(1);
    }

    @Test
    void twoPodsAtOnceSendOneReminder_andACartLeftAMonthExpires() throws Exception {
        String pen = product(50.00, 100);
        alice().contentType(ContentType.JSON).body(List.of(line(pen, 1))).put("/api/cart").then().statusCode(200);
        long cartId = activeCart();
        age(cartId, "2 hours");
        int emailsBefore = emails();

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> { await(start); return reminders.remindDue(); });
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> { await(start); return reminders.remindDue(); });
        start.countDown();
        a.get();
        b.get();
        assertThat(remindersSent(cartId)).isEqualTo(1);
        assertThat(emails()).isEqualTo(emailsBefore + 1);

        age(cartId, "31 days");
        reminders.expire();
        assertThat(jdbc.queryForObject("select status from carts where cart_id = ?", String.class, cartId)).isEqualTo("EXPIRED");
        alice().get("/api/cart").then().statusCode(200).body("lines", hasSize(0));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private String product(double price, int available) {
        String sku = newSku(available);
        setPrice(sku, price);
        return sku;
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private String placeAsAlice(String sku, int quantity) {
        return alice().contentType(ContentType.JSON).body(Map.of("items", List.of(line(sku, quantity))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
    }

    private long activeCart() {
        return jdbc.queryForObject("select cart_id from carts where customer_id = ? and status = 'ACTIVE'", Long.class, ALICE_ID);
    }

    /** As if the customer last changed the cart this long ago, and the job had not looked at it since. */
    private void age(long cartId, String interval) {
        jdbc.update("update carts set updated_at = now() - ?::interval, checked_at = null where cart_id = ?", interval, cartId);
    }

    private int remindersSent(long cartId) {
        return jdbc.queryForObject("select reminders_sent from carts where cart_id = ?", Integer.class, cartId);
    }

    private Map<String, Object> lastEmail() {
        return jdbc.queryForMap("select subject, text from customer_notifications where customer_id = ? and order_id is null "
            + "order by created_at desc limit 1", ALICE_ID);
    }

    private int emails() {
        return jdbc.queryForObject("select count(*) from customer_notifications where customer_id = ? and order_id is null",
            Integer.class, ALICE_ID);
    }

    private void setCartReminders(boolean on) {
        JsonPath profile = alice().get("/api/me/profile").then().statusCode(200).extract().jsonPath();
        Map<String, Object> customer = new HashMap<>();
        customer.put("dateOfBirth", profile.getString("customer.dateOfBirth"));
        customer.put("gender", profile.getString("customer.gender"));
        customer.put("cartReminders", on);
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", profile.getString("fullName"));
        body.put("phone", profile.getString("phone"));
        body.put("customer", customer);
        alice().contentType(ContentType.JSON).body(body).put("/api/me/profile").then().statusCode(200);
    }

    private static JsonPath json(JsonPath path) {
        return path.using(JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
