package com.example.orders;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

/** Multi-warehouse, phase 4: admins open, change and close warehouses, and move stock between them. */
class WarehouseAdminIT extends IntegrationTestBase {

    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Haryana Circle,Ambala Region,Gurgaon Division,Gurgaon H.O,122001,H.O,Delivery,GURGAON,HARYANA,28.4600,77.0300
        West Bengal Circle,Kolkata Region,Kolkata Division,Dankuni S.O,712311,S.O,Delivery,HOOGHLY,WEST BENGAL,22.6800,88.2900
        """;

    @Autowired JdbcTemplate jdbc;

    private long blr;

    @BeforeEach
    void setUp() {
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200);
        blr = jdbc.queryForObject("select warehouse_id from warehouses where is_default", Long.class);
    }

    @Test
    void anAdminOpensAWarehouse_atItsPinsCentre_inTheStateOfItsGstin() {
        Map<String, Object> request = newWarehouse("19", "712311");
        int id = given().contentType(ContentType.JSON).body(request).post("/api/admin/warehouses").then().statusCode(201)
            .body("stateCode", equalTo("19")).body("latitude", equalTo(22.68f)).body("longitude", equalTo(88.29f))
            .body("cutoffTime", equalTo("14:00:00")).body("active", equalTo(true))
            .extract().path("id");

        given().get("/api/admin/warehouses").then().statusCode(200).body("warehouse.code", hasItem(request.get("code")));
        // The shop's list shows the name, never the GSTIN
        anonymous().get("/api/public/warehouses").then().statusCode(200)
            .body("find { it.id == " + id + " }.name", equalTo(request.get("name"))).body("find { it.id == " + id + " }.gstin", nullValue());

        // The same code again; prefixes another warehouse uses
        given().contentType(ContentType.JSON).body(request).post("/api/admin/warehouses").then().statusCode(400)
            .body("reason", containsString("already"));
        Map<String, Object> reused = newWarehouse("19", "712311");
        reused.put("invoicePrefix", "INV");
        given().contentType(ContentType.JSON).body(reused).post("/api/admin/warehouses").then().statusCode(400)
            .body("reason", containsString("already numbers"));
    }

    @Test
    void theGstinMustMatchThePinsState_andBeWellFormed() {
        // A Haryana GSTIN for a West Bengal PIN
        given().contentType(ContentType.JSON).body(newWarehouse("06", "712311")).post("/api/admin/warehouses").then()
            .statusCode(400).body("reason", containsString("is in West Bengal"));
        Map<String, Object> bad = newWarehouse("19", "712311");
        bad.put("gstin", "19-NOT-A-GSTIN");
        given().contentType(ContentType.JSON).body(bad).post("/api/admin/warehouses").then().statusCode(400)
            .body("reason", containsString("15 characters"));
    }

    @Test
    void someThingsCanChange_theRegistrationCannot_andTheDefaultStaysOpen() {
        int id = given().contentType(ContentType.JSON).body(newWarehouse("06", "122001")).post("/api/admin/warehouses")
            .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body(Map.of("name", "Gurugram DC 2", "cutoffTime", "16:30"))
            .patch("/api/admin/warehouses/" + id).then().statusCode(200)
            .body("name", equalTo("Gurugram DC 2")).body("cutoffTime", equalTo("16:30:00"));
        given().contentType(ContentType.JSON).body(Map.of("gstin", "06ZZZZZ9999Z1Z9")).patch("/api/admin/warehouses/" + id)
            .then().statusCode(400).body("reason", containsString("cannot change"));

        given().contentType(ContentType.JSON).body(Map.of("active", false)).patch("/api/admin/warehouses/" + id)
            .then().statusCode(200).body("active", equalTo(false));
        given().contentType(ContentType.JSON).body(Map.of("active", false)).patch("/api/admin/warehouses/" + blr)
            .then().statusCode(400).body("reason", containsString("default warehouse"));
    }

    @Test
    void stockMovesBetweenWarehouses_theTotalsStay_andEachLedgerSaysWhere() {
        int to = given().contentType(ContentType.JSON).body(newWarehouse("06", "122001")).post("/api/admin/warehouses")
            .then().statusCode(201).extract().path("id");
        String code = jdbc.queryForObject("select code from warehouses where warehouse_id = ?", String.class, (long) to);
        String sku = newSku(5);

        given().contentType(ContentType.JSON)
            .body(Map.of("sku", sku, "fromWarehouseId", blr, "toWarehouseId", to, "quantity", 2, "reason", "Festive demand"))
            .post("/api/admin/warehouses/transfers").then().statusCode(201)
            .body("out.type", equalTo("TRANSFER_OUT")).body("out.availableAfter", equalTo(3))
            .body("in.type", equalTo("TRANSFER_IN")).body("in.availableAfter", equalTo(2))
            .body("out.reason", equalTo("To " + code + ": Festive demand"));
        assertStock(sku, 5, 0);
        given().get("/api/admin/catalog/" + sku + "/warehouses").then().statusCode(200)
            .body("find { it.code == 'BLR' }.available", equalTo(3)).body("find { it.code == '" + code + "' }.available", equalTo(2));

        // More than Bengaluru holds; the same warehouse twice
        given().contentType(ContentType.JSON).body(Map.of("sku", sku, "fromWarehouseId", blr, "toWarehouseId", to, "quantity", 9))
            .post("/api/admin/warehouses/transfers").then().statusCode(409).body("reason", containsString("in BLR"));
        given().contentType(ContentType.JSON).body(Map.of("sku", sku, "fromWarehouseId", blr, "toWarehouseId", blr, "quantity", 1))
            .post("/api/admin/warehouses/transfers").then().statusCode(400);
        // Customers cannot
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("sku", sku, "fromWarehouseId", blr, "toWarehouseId", to, "quantity", 1))
            .post("/api/admin/warehouses/transfers").then().statusCode(403);
        assertStock(sku, 5, 0);
    }

    /** A warehouse with a fresh code and prefixes, so tests sharing the database do not collide. */
    private static Map<String, Object> newWarehouse(String stateCode, String pin) {
        String letters = randomLetters(3);
        Map<String, Object> r = new HashMap<>();
        r.put("code", "T" + letters);
        r.put("name", "Test warehouse " + letters);
        r.put("address", "Plot 1, Industrial Area");
        r.put("pin", pin);
        r.put("gstin", stateCode + "AABCO1234F1Z" + ThreadLocalRandom.current().nextInt(1, 9));
        r.put("invoicePrefix", letters.substring(0, 2) + "I");
        r.put("creditNotePrefix", letters.substring(0, 2) + "C");
        return r;
    }

    private static String randomLetters(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            s.append((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
        }
        return s.toString();
    }
}
