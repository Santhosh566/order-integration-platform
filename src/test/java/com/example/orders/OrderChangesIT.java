package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.service.DeliveryCodeGenerator;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doReturn;

/**
 * Changes after an order is placed: a new address (and GST worked out again), another delivery day, more items.
 * The same settings as WalletIT, so the test context is shared. Orders are released to the dispatch area by the
 * carrier-tracking poller; a test that needs an order not packed yet suspends it.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class OrderChangesIT extends IntegrationTestBase {

    private static final String ALICE_ID = "CUST-100";
    private static final String POLLER = "/api/admin/routes/carrier-tracking-poller/";

    // Merged into whatever directory is loaded: a few demo PINs, so slots can be booked and states checked
    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.97,77.64
        Karnataka Circle,Bangalore HQ Region,Bangalore GPO Division,Bangalore G.P.O.,560001,H.O,Delivery,BENGALURU URBAN,KARNATAKA,12.98,77.59
        Tamilnadu Circle,Chennai City Region,Chennai City Central Division,Anna Road H.O,600002,H.O,Delivery,CHENNAI,TAMIL NADU,13.06,80.26
        Maharashtra Circle,Mumbai Region,Mumbai South Division,Kalbadevi H.O,400002,H.O,Delivery,MUMBAI,MAHARASHTRA,18.95,72.83
        """;

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        doReturn("123456").when(codeGenerator).next();
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200);
    }

    @Test
    void anAddressInAnotherStateOfTheRegion_movesTheGst_andKeepsTheTotal() {
        String sku = newSku(10);
        setPrice(sku, 1180.00);
        whileNotPacked(() -> {
            String orderId = placeOrder(sku, 1, Map.of());
            awaitStatus(orderId, OrderStatus.SHIPPED);
            settled(orderId);
            Order before = order(orderId);
            assertThat(before.getPlaceOfSupply()).isEqualTo("29");
            assertThat(before.getCgstTotal()).isEqualByComparingTo("90");

            // A copy opened before something else changed the order is refused
            changeAddress(orderId, chennai(), version(orderId) - 1).statusCode(412).body("status", equalTo("CHANGED"));

            // The PIN must be where the address says
            Map<String, Object> wrongState = new HashMap<>(chennai());
            wrongState.put("state", "Karnataka");
            changeAddress(orderId, wrongState, version(orderId)).statusCode(400).body("reason", containsString("is in Tamil Nadu"));

            // The order is with the South India warehouse: Mumbai is too far
            changeAddress(orderId, mumbai(), version(orderId)).statusCode(409)
                .body("reason", containsString("South India warehouse"));

            changeAddress(orderId, chennai(), version(orderId)).statusCode(200)
                .body("order.placeOfSupply", equalTo("33"))
                .body("order.shippingAddress.city", equalTo("Chennai"))
                .body("changes[0].kind", equalTo("ADDRESS"))
                .body("changes[0].before", containsString("Indiranagar"))
                .body("changes[0].note", containsString("IGST"));
            Order after = order(orderId);
            assertThat(after.getTotal()).isEqualByComparingTo("1180");
            assertThat(after.getCgstTotal()).isEqualByComparingTo("0");
            assertThat(after.getIgstTotal()).isEqualByComparingTo("180");
            assertThat(after.getItems().get(0).getIgstAmount()).isEqualByComparingTo("180");

            changeAddress(orderId, chennai(), version(orderId)).statusCode(400).body("reason", containsString("already the delivery address"));
            alice().get("/api/orders/" + orderId + "/changes").then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].changedBy", equalTo(ALICE_ID))
                .body("[0].byStaff", equalTo(false));
            assertThat(jdbc.queryForObject("select count(*) from customer_notifications where order_id = ? and subject like 'New delivery address%'",
                    Integer.class, orderId)).isEqualTo(1);
            return orderId;
        });
    }

    @Test
    void oncePacked_theStateIsFixedByTheInvoice_nothingCanBeAdded_andADriverEndsChanges() {
        String sku = newSku(10);
        setPrice(sku, 500.00);
        String orderId = placeOrder(sku, 1, Map.of());
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        // The invoice is issued just after the status change, by the next step of the fulfilment route
        org.awaitility.Awaitility.await("invoice for " + orderId).atMost(java.time.Duration.ofSeconds(20))
            .pollInterval(java.time.Duration.ofMillis(100))
            .untilAsserted(() -> assertThat(order(orderId).getInvoiceNumber()).isNotNull());

        changeAddress(orderId, chennai(), null).statusCode(409).body("reason", containsString("tax invoice"));
        addItems(orderId, List.of(line(newSku(5), 1)), false, null).statusCode(409).body("reason", containsString("already packed"));

        // Another street in the same state is fine
        Map<String, Object> office = new HashMap<>(home());
        office.put("line1", "1, MG Road");
        office.put("postalCode", "560001");
        changeAddress(orderId, office, null).statusCode(200).body("order.placeOfSupply", equalTo("29"));

        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take").then().statusCode(200);
        changeAddress(orderId, home(), null).statusCode(409).body("reason", containsString("driver has already taken"));
        changeSlot(orderId, null, null, null).statusCode(409).body("reason", containsString("driver has already taken"));
    }

    @Test
    void theDeliveryDayCanBeMoved_andSupportsChangesShowAsSupport() {
        String sku = newSku(10);
        setPrice(sku, 300.00);
        String orderId = placeOrder(sku, 1, Map.of());
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);

        JsonPath slots = alice().get("/api/checkout/slots?pin=560038").then().statusCode(200).extract().jsonPath();
        assertThat(slots.getBoolean("bookable")).isTrue();
        String day = slots.getString("days[1].date");

        changeSlot(orderId, "2020-01-01", "MORNING", null).statusCode(400).body("reason", containsString("can no longer be booked"));
        changeSlot(orderId, day, null, null).statusCode(400).body("reason", containsString("both a day and a time"));
        changeSlot(orderId, null, null, null).statusCode(400).body("reason", containsString("already on standard delivery"));

        changeSlot(orderId, day, "EVENING", version(orderId)).statusCode(200)
            .body("order.deliveryDate", equalTo(day))
            .body("order.deliveryWindow", equalTo("EVENING"))
            .body("changes[0].before", equalTo("Standard delivery"))
            .body("changes[0].after", containsString("5 PM – 9 PM"));
        changeSlot(orderId, day, "EVENING", null).statusCode(400).body("reason", containsString("already the delivery booked"));

        // Support moves it back to standard delivery for the customer
        given().contentType(ContentType.JSON).body(slotBody(null, null)).post("/api/orders/" + orderId + "/delivery-slot").then()
            .statusCode(200).body("order.deliveryDate", nullValue());

        alice().get("/api/orders/" + orderId + "/changes").then().statusCode(200)
            .body("", hasSize(2))
            .body("[1].byStaff", equalTo(true))
            .body("[1].changedBy", nullValue());
        given().get("/api/orders/" + orderId + "/changes").then().statusCode(200)
            .body("[1].changedBy", org.hamcrest.Matchers.notNullValue());
        // Someone else's order answers like a missing one
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/orders/" + orderId + "/changes").then().statusCode(404);
    }

    @Test
    void itemsAddedBeforePacking_arePaidFromTheWalletAndCard_reserved_andRefundedLikeTheRest() {
        emptyWallet(ALICE_ID);
        credit(ALICE_ID, 100);
        String pen = newSku(10);
        setPrice(pen, 1180.00);
        String lamp = newSku(3);
        setPrice(lamp, 590.00);

        String orderId = whileNotPacked(() -> {
            String id = placeOrder(pen, 1, Map.of());
            awaitStatus(id, OrderStatus.SHIPPED);
            settled(id);

            addItems(id, List.of(line(lamp, 4)), true, null).statusCode(400).body("reason", containsString("Only 3"));
            addItems(id, List.of(line(lamp, 1)), true, version(id) - 1).statusCode(412);
            addItems(id, List.of(line(lamp, 2)), true, version(id)).statusCode(200)
                .body("payment.fromWallet", equalTo(100.0f))
                .body("payment.byCard", equalTo(1080.0f))
                .body("order.total", equalTo(2360.0f))
                .body("order.items", hasSize(2))
                .body("order.items[1].addedAt", org.hamcrest.Matchers.notNullValue())
                .body("changes[0].kind", equalTo("ITEMS_ADDED"))
                .body("changes[0].after", equalTo("2 × Test product"))
                .body("changes[0].amount", equalTo(1180.0f));
            return id;
        });
        assertStock(lamp, 1, 2);
        Order order = order(orderId);
        assertThat(order.getWalletAmount()).isEqualByComparingTo("100");
        assertThat(order.cardAmount()).isEqualByComparingTo("2260");
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("0");
        assertThat(payments(orderId, "CHARGE")).containsExactlyInAnyOrder(order.getPaymentGateway() + " 1180.00",
                "WALLET 100.00", order.getPaymentGateway() + " 1080.00");

        // Packed with the added items on the invoice; cancelled, it all comes back: the card first, then the wallet
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        alice().post("/api/orders/" + orderId + "/cancel").then().statusCode(200);
        assertStock(lamp, 3, 0);
        assertThat(balance(ALICE_ID)).isEqualByComparingTo("100");
        assertThat(order(orderId).getRefundedTotal()).isEqualByComparingTo("2360");
        assertThat(order(orderId).getWalletRefunded()).isEqualByComparingTo("100");
    }

    @Test
    void itemsAddedToACashOnDeliveryOrder_areOwedAtTheDoor() {
        String bread = newSku(10);
        setPrice(bread, 50.00);
        String jam = newSku(10);
        setPrice(jam, 120.00);
        String orderId = whileNotPacked(() -> {
            String id = placeOrder(bread, 2, Map.of("paymentMethod", "COD"));
            awaitStatus(id, OrderStatus.SHIPPED);
            addItems(id, List.of(line(jam, 1)), true, null).statusCode(400).body("reason", containsString("online payment only"));
            addItems(id, List.of(line(jam, 1)), false, null).statusCode(200)
                .body("payment.cashOnDelivery", equalTo(120.0f))
                .body("order.amountDue", equalTo(220.0f))
                .body("changes[0].note", containsString("₹220.00 in cash on delivery"));
            return id;
        });
        assertThat(jdbc.queryForObject("select count(*) from payment_transactions where order_id = ?", Integer.class, orderId)).isZero();
        assertStock(jam, 9, 1);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private <T> T whileNotPacked(java.util.function.Supplier<T> work) {
        given().post(POLLER + "suspend").then().statusCode(200);
        try {
            return work.get();
        } finally {
            given().post(POLLER + "resume").then().statusCode(200);
        }
    }

    /** Waits until the pipeline has finished with the order: its version stays put for half a second. */
    private void settled(String orderId) {
        long[] last = {-1};
        org.awaitility.Awaitility.await("order " + orderId + " to settle").atMost(java.time.Duration.ofSeconds(20))
            .pollInterval(java.time.Duration.ofMillis(500))
            .until(() -> {
                long now = version(orderId);
                boolean same = now == last[0];
                last[0] = now;
                return same;
            });
    }

    /** The version as it is now: the pipeline can still touch an order just after it reaches SHIPPED. */
    private long version(String orderId) {
        return order(orderId).getVersion();
    }

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification driver() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }

    private String placeOrder(String sku, int quantity, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("items", List.of(line(sku, quantity)));
        return alice().contentType(ContentType.JSON).body(body).post("/api/orders").then()
            .statusCode(202).extract().path("orderId");
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private static Map<String, Object> home() {
        return Map.of("recipientName", "Alice Smith", "recipientPhone", "+91-98450-00101",
                "line1", "12, 4th Cross, Indiranagar", "city", "Bengaluru", "state", "Karnataka",
                "postalCode", "560038", "country", "IN");
    }

    private static Map<String, Object> chennai() {
        return Map.of("recipientName", "Alice Smith", "recipientPhone", "+91-98450-00101",
                "line1", "7, Anna Salai", "city", "Chennai", "state", "Tamil Nadu", "postalCode", "600002", "country", "IN");
    }

    private static Map<String, Object> mumbai() {
        return Map.of("recipientName", "Alice Smith", "recipientPhone", "+91-98450-00101",
                "line1", "48, Marine Drive", "city", "Mumbai", "state", "Maharashtra", "postalCode", "400002", "country", "IN");
    }

    private io.restassured.response.ValidatableResponse changeAddress(String orderId, Map<String, Object> address, Long version) {
        return withVersion(alice(), version).contentType(ContentType.JSON).body(Map.of("address", address))
            .post("/api/orders/" + orderId + "/address").then();
    }

    private io.restassured.response.ValidatableResponse changeSlot(String orderId, String date, String window, Long version) {
        return withVersion(alice(), version).contentType(ContentType.JSON).body(slotBody(date, window))
            .post("/api/orders/" + orderId + "/delivery-slot").then();
    }

    private static Map<String, Object> slotBody(String date, String window) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", date);
        body.put("window", window);
        return body;
    }

    private io.restassured.response.ValidatableResponse addItems(String orderId, List<Map<String, Object>> items,
                                                                  boolean useWallet, Long version) {
        return withVersion(alice(), version).contentType(ContentType.JSON)
            .body(Map.of("items", items, "useWallet", useWallet))
            .post("/api/orders/" + orderId + "/items").then();
    }

    private static RequestSpecification withVersion(RequestSpecification request, Long version) {
        return version == null ? request : request.header("If-Match", "\"" + version + "\"");
    }

    private void credit(String customerId, int amount) {
        given().contentType(ContentType.JSON).body(Map.of("amount", amount, "note", "OrderChangesIT"))
            .post("/api/admin/users/" + customerId + "/wallet/credits").then().statusCode(200);
    }

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
}
