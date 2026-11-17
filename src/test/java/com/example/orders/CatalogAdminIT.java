package com.example.orders;

import com.example.orders.model.InventoryItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.StockMovement;
import com.example.orders.repository.StockMovementRepository;
import com.example.orders.service.CatalogAdminService;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Catalogue and stock administration: products, price history, the stock ledger and alerts.
 */
class CatalogAdminIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StockMovementRepository movements;

    @Autowired
    CatalogAdminService catalogAdminService;

    // ── Products ─────────────────────────────────────────────────────────────

    @Test
    void aNewProduct_isSoldInTheShop_andStartsItsLedgerAndPriceHistory() {
        String sku = createProduct("1180.00", 7, 2).extract().path("sku");

        anonymous().get("/api/public/catalog").then().statusCode(200)
            .body("sku", hasItem(sku))
            .body("find { it.sku == '" + sku + "' }.stockStatus", equalTo("IN_STOCK"))
            .body("find { it.sku == '" + sku + "' }.unitPrice", equalTo(1180.0f));

        given().get("/api/admin/catalog/" + sku).then().statusCode(200)
            .body("product.available", equalTo(7))
            .body("prices.size()", equalTo(1))
            .body("prices[0].state", equalTo("APPLIED"))
            .body("recentMovements[0].type", equalTo("OPENING"))
            .body("recentMovements[0].availableChange", equalTo(7))
            .body("recentMovements[0].createdBy", equalTo("ADM-001"));
    }

    @Test
    void badOrDuplicateProducts_areRefused_andOnlyAdminsMayEdit() {
        String sku = createProduct("100.00", 1, 0).extract().path("sku");

        given().contentType(ContentType.JSON).body(product(sku, "100.00", 1, 0)).post("/api/admin/catalog")
            .then().statusCode(409);
        Map<String, Object> badHsn = product(newSkuName(), "100.00", 1, 0);
        badHsn.put("hsnCode", "12");
        given().contentType(ContentType.JSON).body(badHsn).post("/api/admin/catalog")
            .then().statusCode(400).body("reason", containsString("hsnCode"));
        Map<String, Object> badRate = product(newSkuName(), "100.00", 1, 0);
        badRate.put("gstRate", 7);
        given().contentType(ContentType.JSON).body(badRate).post("/api/admin/catalog")
            .then().statusCode(400).body("reason", containsString("gstRate"));

        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(product(newSkuName(), "1.00", 1, 0))
            .post("/api/admin/catalog").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/admin/catalog").then().statusCode(403);
    }

    @Test
    void aRetiredProduct_cannotBeOrdered_andLeavesTheShop() {
        String sku = createProduct("500.00", 10, 0).extract().path("sku");

        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", 1, "active", false))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200).body("active", equalTo(false));

        submitOrder(Map.of("customerId", "CUST-100", "items", List.of(Map.of("sku", sku, "quantity", 1))))
            .statusCode(400).body("reason", containsString("no longer sold"));
        anonymous().get("/api/public/catalog").then().body("sku", not(hasItem(sku)));
    }

    @Test
    void twoAdminsEditingAtOnce_theSecondIsToldToReload() {
        String sku = createProduct("500.00", 10, 0).extract().path("sku");

        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", 1, "name", "Renamed once"))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200).body("version", equalTo(2));
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", 1, "name", "Renamed again"))
            .patch("/api/admin/catalog/" + sku).then().statusCode(409).body("reason", containsString("Reload"));
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", 1, "unitPrice", 1))
            .post("/api/admin/catalog/" + sku + "/prices").then().statusCode(409);

        assertThat(inventoryRepository.findById(sku).orElseThrow().getProductName()).isEqualTo("Renamed once");
    }

    // ── Prices ───────────────────────────────────────────────────────────────

    @Test
    void aPriceChange_appliesToNewOrders_andNeverToOrdersAlreadyPlaced() {
        String sku = createProduct("1180.00", 10, 0).extract().path("sku");
        String before = placeFor(sku, 1);

        given().contentType(ContentType.JSON)
            .body(Map.of("expectedVersion", 1, "unitPrice", 2360, "reason", "Supplier price rise"))
            .post("/api/admin/catalog/" + sku + "/prices").then().statusCode(200)
            .body("product.unitPrice", equalTo(2360.0f));
        String after = placeFor(sku, 1);

        assertThat(order(before).getTotal()).isEqualByComparingTo("1180.00");
        assertThat(order(after).getTotal()).isEqualByComparingTo("2360.00");
    }

    @Test
    void aFuturePrice_waitsUntilItIsDue_andCanBeCancelledUntilThen() {
        String sku = createProduct("1000.00", 10, 0).extract().path("sku");

        Integer cancelled = schedule(sku, 1, "1500.00", LocalDateTime.now().plusDays(7)).extract().path("price.id");
        given().delete("/api/admin/catalog/" + sku + "/prices/" + cancelled).then().statusCode(200)
            .body("state", equalTo("CANCELLED"));

        Integer due = schedule(sku, 1, "1200.00", LocalDateTime.now().plusDays(1)).extract().path("price.id");
        assertThat(price(sku)).isEqualByComparingTo("1000.00");

        // A day passes
        jdbcTemplate.update("update product_prices set effective_from = now() - interval '1 minute' where id = ?", due);
        catalogAdminService.applyDuePrices();

        assertThat(price(sku)).isEqualByComparingTo("1200.00");
        given().delete("/api/admin/catalog/" + sku + "/prices/" + due).then().statusCode(409);
        given().get("/api/admin/catalog/" + sku).then()
            .body("prices.state", org.hamcrest.Matchers.containsInAnyOrder("APPLIED", "CANCELLED", "APPLIED"));
    }

    // ── Stock ────────────────────────────────────────────────────────────────

    @Test
    void stockChanges_areRecordedWithWhoAndWhy_andCannotGoBelowZero() {
        String sku = createProduct("100.00", 5, 0).extract().path("sku");

        stock(sku, "RECEIPT", 10, "PO-4411").statusCode(201).body("availableAfter", equalTo(15));
        stock(sku, "WRITE_OFF", 3, null).statusCode(400).body("reason", containsString("reason"));
        stock(sku, "WRITE_OFF", 3, "Water damage").statusCode(201).body("availableAfter", equalTo(12));
        stock(sku, "ADJUSTMENT", -2, "Count on 6 Nov").statusCode(201).body("availableAfter", equalTo(10));
        stock(sku, "ADJUSTMENT", -20, "Typo").statusCode(409);
        stock(sku, "RESERVE", 1, "Sneaky").statusCode(400);

        assertStock(sku, 10, 0);
        given().get("/api/admin/catalog/" + sku + "/movements").then().statusCode(200)
            .body("items.type", equalTo(List.of("ADJUSTMENT", "WRITE_OFF", "RECEIPT", "OPENING")))
            .body("items.createdBy", everyItemIs("ADM-001"));
    }

    @Test
    void theOrderPipeline_writesItsStockMovements_andTheLedgerAddsUpToTheBalance() {
        String sku = createProduct("100.00", 10, 0).extract().path("sku");
        String orderId = placeFor(sku, 2);
        awaitStatus(orderId, OrderStatus.DELIVERED);

        List<StockMovement> forOrder = movements.findByOrderIdOrderById(orderId);
        assertThat(forOrder).extracting(StockMovement::getType)
            .containsExactly(StockMovement.Type.RESERVE, StockMovement.Type.SHIP);
        assertThat(forOrder.get(0).getAvailableChange()).isEqualTo(-2);
        assertThat(forOrder.get(0).getReservedChange()).isEqualTo(2);
        assertThat(forOrder.get(1).getReservedChange()).isEqualTo(-2);
        assertThat(forOrder).allSatisfy(m -> assertThat(m.getCreatedBy()).isEqualTo("system"));

        assertLedgerMatches(sku);
    }

    @Test
    void cancellingAnOrder_releasesItsStockInTheLedger() {
        String sku = createProduct("100.00", 10, 0).extract().path("sku");
        String orderId = placeFor(sku, 3);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        cancel(orderId).statusCode(200);

        assertThat(movements.findByOrderIdOrderById(orderId)).extracting(StockMovement::getType)
            .containsExactly(StockMovement.Type.RESERVE, StockMovement.Type.RELEASE);
        assertStock(sku, 10, 0);
        assertLedgerMatches(sku);
    }

    @Test
    void fallingToTheReorderLevel_emailsTheInventoryTeamOnce() {
        String sku = createProduct("100.00", 5, 3).extract().path("sku");

        stock(sku, "WRITE_OFF", 2, "Damaged").statusCode(201);
        stock(sku, "WRITE_OFF", 1, "Damaged").statusCode(201);

        String query = "select status from customer_notifications where recipient_email = 'inventory@example.com' "
                + "and subject like ?";
        Awaitility.await("the low-stock email").atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(jdbcTemplate.queryForList(query, String.class, "%" + sku + "%")).containsExactly("SENT"));
        given().get("/api/admin/catalog/" + sku).then().body("product.lowStock", equalTo(true));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static org.hamcrest.Matcher<Iterable<? extends String>> everyItemIs(String value) {
        return org.hamcrest.Matchers.everyItem(equalTo(value));
    }

    private void assertLedgerMatches(String sku) {
        List<StockMovement> all = movements.findBySkuOrderById(sku);
        InventoryItem item = inventoryRepository.findById(sku).orElseThrow();
        assertThat(all.stream().mapToInt(StockMovement::getAvailableChange).sum()).isEqualTo(item.getAvailableQuantity());
        assertThat(all.stream().mapToInt(StockMovement::getReservedChange).sum()).isEqualTo(item.getReservedQuantity());
        StockMovement last = all.get(all.size() - 1);
        assertThat(last.getAvailableAfter()).isEqualTo(item.getAvailableQuantity());
        assertThat(last.getReservedAfter()).isEqualTo(item.getReservedQuantity());
    }

    private java.math.BigDecimal price(String sku) {
        return inventoryRepository.findById(sku).orElseThrow().getUnitPrice();
    }

    private String placeFor(String sku, int quantity) {
        return submitOrder(Map.of("customerId", "CUST-100", "items", List.of(Map.of("sku", sku, "quantity", quantity))))
            .statusCode(202).extract().path("orderId");
    }

    private ValidatableResponse schedule(String sku, int version, String price, LocalDateTime from) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("expectedVersion", version, "unitPrice", new java.math.BigDecimal(price),
                    "effectiveFrom", from.withNano(0).toString()))
            .post("/api/admin/catalog/" + sku + "/prices").then().statusCode(200);
    }

    private ValidatableResponse stock(String sku, String type, int quantity, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("quantity", quantity);
        body.put("reason", reason);
        return given().contentType(ContentType.JSON).body(body).post("/api/admin/catalog/" + sku + "/stock").then();
    }

    private ValidatableResponse createProduct(String price, int stock, int reorderLevel) {
        return given().contentType(ContentType.JSON).body(product(newSkuName(), price, stock, reorderLevel))
            .post("/api/admin/catalog").then().statusCode(201);
    }

    private static Map<String, Object> product(String sku, String price, int stock, int reorderLevel) {
        Map<String, Object> body = new HashMap<>();
        body.put("sku", sku);
        body.put("name", "Standing Desk " + sku);
        body.put("hsnCode", "9403");
        body.put("unitPrice", new java.math.BigDecimal(price));
        body.put("gstRate", 18);
        body.put("openingStock", stock);
        body.put("reorderLevel", reorderLevel);
        return body;
    }

    private static String newSkuName() {
        return "DESK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
