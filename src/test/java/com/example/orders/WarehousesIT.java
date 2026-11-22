package com.example.orders;

import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** Multi-warehouse, phase 1: stock is kept per warehouse, and the product's totals stay their sum. */
class WarehousesIT extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    private long blr;
    private long ggn;

    @BeforeEach
    void warehouses() {
        blr = jdbc.queryForObject("select warehouse_id from warehouses where is_default", Long.class);
        // A second warehouse, as an admin or the sample SQL would add it
        jdbc.update("insert into warehouses (code, name, address, pin, state_code, gstin, location, invoice_prefix, credit_note_prefix) "
                + "values ('GGN', 'Gurugram fulfilment centre', 'Sector 37, Gurugram, Haryana', '122001', '06', '06AABCO1234F1Z9', "
                + "ST_SetSRID(ST_MakePoint(77.0266, 28.4595), 4326)::geography, 'GGI', 'GGC') on conflict (code) do nothing");
        ggn = jdbc.queryForObject("select warehouse_id from warehouses where code = 'GGN'", Long.class);
    }

    @Test
    void theShopsFirstWarehouseIsBengaluru_withTheSellersGstin() {
        assertThat(jdbc.queryForMap("select code, state_code, gstin, ST_AsText(location) as at, is_default from warehouses "
                + "where warehouse_id = ?", blr))
            .containsEntry("code", "BLR").containsEntry("state_code", "29").containsEntry("gstin", "29AABCO1234F1Z5")
            .containsEntry("at", "POINT(77.6245 12.9352)").containsEntry("is_default", true);
        given().get("/api/admin/warehouses").then().statusCode(200)
            .body("warehouse.code", hasItem("BLR")).body("find { it.warehouse.code == 'BLR' }.warehouse.isDefault", equalTo(true));
    }

    @Test
    void anOrderReservesAndReleasesInItsWarehouse_andTheTotalsFollow() {
        String sku = newSku(10);
        String orderId = placeOrder(sku, 3, 25.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);

        assertThat(order(orderId).getWarehouseId()).isEqualTo(blr);
        assertThat(balance(blr, sku)).containsEntry("available_quantity", 7).containsEntry("reserved_quantity", 3);
        assertStock(sku, 7, 3);
        assertThat(jdbc.queryForObject("select count(*) from stock_movements where sku = ? and order_id = ? "
                + "and movement_type = 'RESERVE' and warehouse_id = ?", Integer.class, sku, orderId, blr)).isEqualTo(1);

        cancel(orderId).statusCode(200);
        awaitStatus(orderId, OrderStatus.CANCELLED);
        assertThat(balance(blr, sku)).containsEntry("available_quantity", 10).containsEntry("reserved_quantity", 0);
        assertStock(sku, 10, 0);
        assertTotalsAreTheSum(sku);
    }

    @Test
    void goodsReceivedInAnotherWarehouse_countInTheTotals_andEachWarehouseKeepsItsOwnLedger() {
        String sku = newSku(10);
        given().contentType(ContentType.JSON).body(Map.of("type", "RECEIPT", "quantity", 4, "warehouseId", ggn))
            .post("/api/admin/catalog/" + sku + "/stock").then().statusCode(201)
            .body("warehouseId", equalTo((int) ggn)).body("availableAfter", equalTo(4));
        assertStock(sku, 14, 0);
        assertTotalsAreTheSum(sku);

        given().get("/api/admin/catalog/" + sku + "/warehouses").then().statusCode(200)
            .body("find { it.code == 'BLR' }.available", equalTo(10))
            .body("find { it.code == 'GGN' }.available", equalTo(4));

        // Without a warehouse it goes to the default one, as before
        given().contentType(ContentType.JSON).body(Map.of("type", "RECEIPT", "quantity", 1))
            .post("/api/admin/catalog/" + sku + "/stock").then().statusCode(201).body("warehouseId", equalTo((int) blr));

        // A warehouse cannot write off more than it holds, even though the shop as a whole has more
        given().contentType(ContentType.JSON).body(Map.of("type", "WRITE_OFF", "quantity", 5, "reason", "Damaged", "warehouseId", ggn))
            .post("/api/admin/catalog/" + sku + "/stock").then().statusCode(409).body("reason", containsString("in GGN"));
        assertTotalsAreTheSum(sku);
    }

    private Map<String, Object> balance(long warehouse, String sku) {
        return jdbc.queryForMap("select available_quantity, reserved_quantity from warehouse_stock where warehouse_id = ? and sku = ?",
            warehouse, sku);
    }

    private void assertTotalsAreTheSum(String sku) {
        Map<String, Object> sums = jdbc.queryForMap("select sum(available_quantity)::int as a, sum(reserved_quantity)::int as r "
            + "from warehouse_stock where sku = ?", sku);
        Map<String, Object> totals = jdbc.queryForMap("select available_quantity as a, reserved_quantity as r from inventory where sku = ?", sku);
        assertThat(totals).isEqualTo(sums);
    }
}
