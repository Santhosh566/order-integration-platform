package com.example.orders;

import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** Multi-warehouse, phase 3: drivers work from one warehouse, and delivery dates start from the one that ships. */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class WarehouseDeliveryIT extends IntegrationTestBase {

    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.9700,77.6400
        Delhi Circle,Delhi Region,New Delhi Central Division,Connaught Place S.O,110001,S.O,Delivery,NEW DELHI,DELHI,28.6300,77.2200
        """;

    @Autowired JdbcTemplate jdbc;

    private long blr;
    private long ggn;
    private String driverTwo;

    @BeforeEach
    void setUp() {
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200);
        blr = jdbc.queryForObject("select warehouse_id from warehouses where code = 'BLR'", Long.class);
        jdbc.update("insert into warehouses (code, name, address, pin, state_code, gstin, location, invoice_prefix, credit_note_prefix) "
                + "values ('GGN', 'Gurugram fulfilment centre', 'Sector 37, Gurugram, Haryana', '122001', '06', '06AABCO1234F1Z9', "
                + "ST_SetSRID(ST_MakePoint(77.0266, 28.4595), 4326)::geography, 'GGI', 'GGC') "
                + "on conflict (code) do update set active = true");
        ggn = jdbc.queryForObject("select warehouse_id from warehouses where code = 'GGN'", Long.class);
        driverTwo = jdbc.queryForObject("select user_id from app_users where keycloak_subject = ?", String.class, TestTokens.DRIVER_TWO);
    }

    @Test
    void aDriverSeesAndTakesOnlyTheirWarehousesOrders_andAnAdminCannotAssignAcross() {
        String sku = product(0, 3);
        String orderId = placeForDelhi(sku);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        assertThat(order(orderId).getWarehouseId()).isEqualTo(ggn);

        // Both drivers work from Bengaluru: they do not see it, cannot take it, and cannot be given it
        driver(TestTokens.DRIVER_ONE).get("/api/delivery/available").then().statusCode(200).body("orderId", not(hasItem(orderId)));
        driver(TestTokens.DRIVER_ONE).contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take")
            .then().statusCode(409).body("reason", containsString("ships from GGN"));
        given().contentType(ContentType.JSON).body(Map.of("deliveryUserId", driverTwo))
            .post("/api/admin/delivery/orders/" + orderId + "/assign").then().statusCode(400).body("reason", containsString("works from BLR"));
        // The admin's board shows every warehouse
        given().get("/api/admin/delivery/waiting").then().statusCode(200)
            .body("find { it.orderId == '" + orderId + "' }.warehouse", equalTo("GGN"));

        try {
            given().contentType(ContentType.JSON).body(Map.of("warehouseId", ggn)).put("/api/admin/users/" + driverTwo + "/warehouse")
                .then().statusCode(200).body("code", equalTo("GGN"));
            driver(TestTokens.DRIVER_TWO).get("/api/delivery/warehouse").then().statusCode(200).body("code", equalTo("GGN"));
            driver(TestTokens.DRIVER_TWO).get("/api/delivery/available").then().statusCode(200).body("orderId", hasItem(orderId));
            driver(TestTokens.DRIVER_TWO).contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/take")
                .then().statusCode(200);
            given().get("/api/admin/delivery/drivers").then().statusCode(200)
                .body("find { it.userId == '" + driverTwo + "' }.warehouse", equalTo("GGN"));
        } finally {
            jdbc.update("update app_users set home_warehouse_id = ? where user_id = ?", blr, driverTwo);
        }
        // Only drivers have a warehouse
        given().contentType(ContentType.JSON).body(Map.of("warehouseId", ggn)).put("/api/admin/users/CUST-100/warehouse")
            .then().statusCode(400).body("reason", containsString("not a delivery user"));
    }

    @Test
    void theProductPageDatesStartFromTheNearestWarehouseWithIt() {
        String nearDelhi = product(0, 3);
        String onlyInBengaluru = product(3, 0);

        JsonPath fromGurugram = estimate(nearDelhi, "110001");
        assertThat(fromGurugram.getString("data.deliveryEstimate.outcome")).isEqualTo("DELIVERABLE");
        assertThat(fromGurugram.getString("data.deliveryEstimate.shipsFrom")).isEqualTo("Gurugram fulfilment centre");
        JsonPath fromBengaluru = estimate(onlyInBengaluru, "110001");
        assertThat(fromBengaluru.getString("data.deliveryEstimate.shipsFrom")).isEqualTo("Bengaluru fulfilment centre");
        // 25 km versus 1,700 km: the Gurugram parcel arrives sooner
        assertThat(LocalDate.parse(fromGurugram.getString("data.deliveryEstimate.earliest")))
            .isBefore(LocalDate.parse(fromBengaluru.getString("data.deliveryEstimate.earliest")));

        // Nowhere in stock
        assertThat(estimate(product(0, 0), "110001").getString("data.deliveryEstimate.outcome")).isEqualTo("OUT_OF_STOCK");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private JsonPath estimate(String sku, String pin) {
        String query = "query D($sku: ID!, $pin: String!) { deliveryEstimate(sku: $sku, pin: $pin) { outcome earliest shipsFrom } }";
        return anonymous().contentType(ContentType.JSON).body(Map.of("query", query, "variables", Map.of("sku", sku, "pin", pin)))
            .post("/graphql").then().statusCode(200).extract().jsonPath();
    }

    /** ₹999, with this stock in Bengaluru and in Gurugram. */
    private String product(int inBlr, int inGgn) {
        String sku = newSku(0);
        setPrice(sku, 999.00);
        jdbc.update("insert into warehouse_stock (warehouse_id, sku, available_quantity) values (?, ?, ?), (?, ?, ?) "
            + "on conflict (warehouse_id, sku) do update set available_quantity = excluded.available_quantity",
            blr, sku, inBlr, ggn, sku, inGgn);
        jdbc.update("update inventory set available_quantity = ? where sku = ?", inBlr + inGgn, sku);
        return sku;
    }

    private String placeForDelhi(String sku) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(Map.of("sku", sku, "quantity", 1)));
        body.put("shippingAddress", Map.of("recipientName", "Alice Smith", "recipientPhone", "+91-98450-00101",
            "line1", "12, Janpath", "city", "New Delhi", "state", "Delhi", "postalCode", "110001", "country", "IN"));
        return asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(body).post("/api/orders").then()
            .statusCode(202).extract().path("orderId");
    }

    private RequestSpecification driver(String token) {
        return asUser(token, "DELIVERY");
    }
}
