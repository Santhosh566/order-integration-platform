package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.service.DeliveryCodeGenerator;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.doReturn;

/**
 * Gift cards are redeemed whole into a customer's wallet, and the wallet pays for orders: as much as it holds,
 * the card the rest. Refunds go to the card first and back to the wallet only for what the card did not pay.
 * The same settings as PartialCancelIT, so the test context is shared: orders wait for a driver.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class WalletIT extends IntegrationTestBase {

    private static final String ALICE_ID = "CUST-100";

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixedCode() {
        doReturn("123456").when(codeGenerator).next();
    }

    @Test
    void aGiftCardIsRedeemedOnce_intoTheWallet_andCanBeStoppedBeforeThat() {
        emptyWallet(ALICE_ID);
        List<String> codes = given().contentType(ContentType.JSON)
            .body(Map.of("amount", 500, "count", 2, "label", "WalletIT"))
            .post("/api/admin/gift-cards").then()
            .statusCode(201)
            .body("cards", hasSize(2))
            .body("cards[0].code", org.hamcrest.Matchers.matchesPattern("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"))
            .extract().path("cards.code");
        // Only a hash is kept: the list shows the last four characters
        given().get("/api/admin/gift-cards").then().statusCode(200)
            .body("find { it.maskedCode == 'XXXX-XXXX-XXXX-" + codes.get(0).substring(15) + "' }.status", equalTo("ACTIVE"));

        // Typed in lower case without dashes, as people do
        String typed = codes.get(0).replace("-", "").toLowerCase();
        alice().contentType(ContentType.JSON).body(Map.of("code", typed)).post("/api/wallet/gift-cards").then()
            .statusCode(200)
            .body("amount", equalTo(500.0f))
            .body("balance", equalTo(500.0f));
        alice().get("/api/wallet").then().statusCode(200)
            .body("balance", equalTo(500.0f))
            .body("available", equalTo(500.0f))
            .body("history[0].kind", equalTo("GIFT_CARD"))
            .body("history[0].giftCardLastFour", equalTo(codes.get(0).substring(15)));

        alice().contentType(ContentType.JSON).body(Map.of("code", codes.get(0))).post("/api/wallet/gift-cards").then()
            .statusCode(400).body("reason", containsString("already added this card"));
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("500");

        // A lost card is stopped; a redeemed one cannot be
        long second = id(codes.get(1));
        given().contentType(ContentType.JSON).body(Map.of("reason", "Lost in the post"))
            .post("/api/admin/gift-cards/" + second + "/void").then().statusCode(200).body("status", equalTo("VOID"));
        alice().contentType(ContentType.JSON).body(Map.of("code", codes.get(1))).post("/api/wallet/gift-cards").then()
            .statusCode(400).body("reason", containsString("stopped"));
        given().contentType(ContentType.JSON).body(Map.of("reason", "Oops"))
            .post("/api/admin/gift-cards/" + id(codes.get(0)) + "/void").then().statusCode(409);

        // Customers cannot issue cards
        alice().contentType(ContentType.JSON).body(Map.of("amount", 500)).post("/api/admin/gift-cards").then().statusCode(403);
    }

    @Test
    void guessingCodes_isStoppedAfterFiveMisses() {
        String code = given().contentType(ContentType.JSON).body(Map.of("amount", 100))
            .post("/api/admin/gift-cards").then().statusCode(201).extract().path("cards[0].code");
        RequestSpecification bob = asUser(TestTokens.BOB, "CUSTOMER");
        for (int i = 0; i < 5; i++) {
            asUser(TestTokens.BOB, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("code", "AAAA-BBBB-CCCC-DDD" + (i + 2)))
                .post("/api/wallet/gift-cards").then().statusCode(400).body("reason", containsString("not valid"));
        }
        // Even a real code waits now
        bob.contentType(ContentType.JSON).body(Map.of("code", code)).post("/api/wallet/gift-cards").then()
            .statusCode(429).body("status", equalTo("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void theWalletPaysPartOfAnOrder_andRefundsGoToTheCardFirst() {
        emptyWallet(ALICE_ID);
        credit(ALICE_ID, 500);
        String sku = newSku(10);
        setPrice(sku, 1180.00);

        String orderId = placeOrder(sku, 2, true);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        Order order = order(orderId);
        assertThat(order.getWalletAmount()).isEqualByComparingTo("500");
        assertThat(order.cardAmount()).isEqualByComparingTo("1860");
        assertThat(order.getPaymentGateway()).isIn("STRIPE", "ADYEN");
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("0");
        assertThat(payments(orderId, "CHARGE")).containsExactlyInAnyOrder("WALLET 500.00", order.getPaymentGateway() + " 1860.00");

        // One unit cancelled: back to the card, which paid more than that
        alice().contentType(ContentType.JSON).body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders/" + orderId + "/cancel-items").then().statusCode(200);
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("0");
        assertThat(payments(orderId, "REFUND")).containsExactly(order.getPaymentGateway() + " 1180.00");

        // The rest: what is left on the card (680), then the wallet's 500
        alice().post("/api/orders/" + orderId + "/cancel").then().statusCode(200);
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("500");
        assertThat(payments(orderId, "REFUND")).containsExactlyInAnyOrder(order.getPaymentGateway() + " 1180.00",
                order.getPaymentGateway() + " 680.00", "WALLET 500.00");
        Order cancelled = order(orderId);
        assertThat(cancelled.getRefundedTotal()).isEqualByComparingTo("2360");
        assertThat(cancelled.getWalletRefunded()).isEqualByComparingTo("500");
        alice().get("/api/wallet").then()
            .body("history[0].kind", equalTo("ORDER_REFUND"))
            .body("history[0].orderId", equalTo(orderId))
            .body("history[1].kind", equalTo("ORDER_PAYMENT"))
            .body("history[1].amount", equalTo(-500.0f));
    }

    @Test
    void theWalletCanPayAWholeOrder_withoutACard() {
        emptyWallet(ALICE_ID);
        credit(ALICE_ID, 2000);
        String sku = newSku(10);
        setPrice(sku, 1180.00);

        String orderId = placeOrder(sku, 1, true);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        Order order = order(orderId);
        assertThat(order.getWalletAmount()).isEqualByComparingTo("1180");
        assertThat(order.cardAmount()).isEqualByComparingTo("0");
        assertThat(order.getPaymentGateway()).isEqualTo("WALLET");
        assertThat(order.getPaymentTransactionId()).startsWith("WAL-");
        assertThat(payments(orderId, "CHARGE")).containsExactly("WALLET 1180.00");
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("820");

        alice().get("/api/orders/" + orderId).then()
            .body("walletAmount", equalTo(1180.0f))
            .body("cardAmount", equalTo(0.0f));

        alice().post("/api/orders/" + orderId + "/cancel").then().statusCode(200);
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("2000");
    }

    @Test
    void theWalletIsRefused_withCashOnDelivery_orWhenEmpty() {
        emptyWallet(ALICE_ID);
        String sku = newSku(10);
        setPrice(sku, 500.00);
        orderRequest(sku, 1, true, "PREPAID").statusCode(400).body("reason", containsString("wallet is empty"));

        credit(ALICE_ID, 100);
        orderRequest(sku, 1, true, "COD").statusCode(400).body("reason", containsString("online payment only"));
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("100");
    }

    @Test
    void adminsGiveAndTakeBackStoreCredit_butNeverBelowZero() {
        emptyWallet(ALICE_ID);
        given().contentType(ContentType.JSON).body(Map.of("amount", 250, "note", "Late delivery"))
            .post("/api/admin/users/" + ALICE_ID + "/wallet/credits").then()
            .statusCode(200).body("kind", equalTo("ADMIN_CREDIT")).body("balanceAfter", equalTo(250.0f))
            .body("reference", startsWith("WAL-"));
        given().contentType(ContentType.JSON).body(Map.of("amount", 300, "note", "Given twice"))
            .post("/api/admin/users/" + ALICE_ID + "/wallet/debits").then()
            .statusCode(400).body("reason", containsString("less than"));
        given().contentType(ContentType.JSON).body(Map.of("amount", 250))
            .post("/api/admin/users/" + ALICE_ID + "/wallet/debits").then()
            .statusCode(400).body("reason", containsString("Say why"));
        given().contentType(ContentType.JSON).body(Map.of("amount", 250, "note", "Given twice"))
            .post("/api/admin/users/" + ALICE_ID + "/wallet/debits").then()
            .statusCode(200).body("balanceAfter", equalTo(0.0f));
        given().get("/api/admin/users/" + ALICE_ID + "/wallet").then().statusCode(200)
            .body("balance", equalTo(0.0f))
            .body("history", hasSize(2))
            .body("history[0].createdBy", org.hamcrest.Matchers.notNullValue())
            .body("history[0].note", equalTo("Given twice"));
        alice().contentType(ContentType.JSON).body(Map.of("amount", 1000, "note", "Me"))
            .post("/api/admin/users/" + ALICE_ID + "/wallet/credits").then().statusCode(403);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private String placeOrder(String sku, int quantity, boolean useWallet) {
        return orderRequest(sku, quantity, useWallet, "PREPAID").statusCode(202).extract().path("orderId");
    }

    private io.restassured.response.ValidatableResponse orderRequest(String sku, int quantity, boolean useWallet, String method) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("sku", sku, "quantity", quantity)));
        body.put("useWallet", useWallet);
        body.put("paymentMethod", method);
        return alice().contentType(ContentType.JSON).body(body).post("/api/orders").then();
    }

    private void credit(String customerId, int amount) {
        given().contentType(ContentType.JSON).body(Map.of("amount", amount, "note", "WalletIT"))
            .post("/api/admin/users/" + customerId + "/wallet/credits").then().statusCode(200);
    }

    /** Wallets outlive a test, so each test starts from an empty one. */
    private void emptyWallet(String customerId) {
        jdbc.update("delete from wallet_transactions where customer_id = ?", customerId);
        jdbc.update("update wallets set balance = 0 where customer_id = ?", customerId);
    }

    private BigDecimal balance(String customerId) {
        List<BigDecimal> found = jdbc.queryForList("select balance from wallets where customer_id = ?", BigDecimal.class, customerId);
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    private List<String> payments(String orderId, String kind) {
        return jdbc.queryForList("select gateway || ' ' || amount from payment_transactions where order_id = ? and kind = ? "
                + "order by payment_id", String.class, orderId, kind);
    }

    private long id(String code) {
        String hash = jdbc.queryForObject("select encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class,
                code.replace("-", ""));
        return jdbc.queryForObject("select gift_card_id from gift_cards where code_hash = ?", Long.class, hash);
    }
}
