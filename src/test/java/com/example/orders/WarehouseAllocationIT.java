package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Multi-warehouse, phase 2: an order ships from the nearest warehouse that has all of it, is taxed and invoiced under
 * that warehouse's GST registration, and is never split.
 */
@org.springframework.test.context.TestPropertySource(properties = {
        // Orders wait for a driver at READY_FOR_DISPATCH instead of being delivered by the simulation timer
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class WarehouseAllocationIT extends IntegrationTestBase {

    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.9700,77.6400
        Delhi Circle,Delhi Region,New Delhi Central Division,Connaught Place S.O,110001,S.O,Delivery,NEW DELHI,DELHI,28.6300,77.2200
        Haryana Circle,Ambala Region,Gurgaon Division,Gurgaon H.O,122001,H.O,Delivery,GURGAON,HARYANA,28.4600,77.0300
        """;
    private static final String CONSUMER = "/api/admin/routes/order-event-consumer/";

    @Autowired JdbcTemplate jdbc;

    private long blr;
    private long ggn;

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
    }

    @Test
    void aDelhiOrderShipsFromGurugram_withIgstAndTheHaryanaGstin_andItsOwnInvoiceSeries() {
        String sku = product(5, 5);
        given().contentType(ContentType.JSON).body(quote(sku, delhi())).post("/api/checkout/quote").then().statusCode(403);
        alice().contentType(ContentType.JSON).body(quote(sku, delhi())).post("/api/checkout/quote").then().statusCode(200)
            .body("shipsFrom.code", equalTo("GGN")).body("shippingProblem", nullValue())
            .body("igstTotal", equalTo(152.39f)).body("cgstTotal", equalTo(0f));

        String orderId = place(sku, 1, delhi());
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        Order order = awaitInvoice(orderId);
        assertThat(order.getWarehouseId()).isEqualTo(ggn);
        assertThat(order.getSellerGstin()).isEqualTo("06AABCO1234F1Z9");
        assertThat(order.getIgstTotal()).isPositive();
        assertThat(order.getInvoiceNumber()).startsWith("GGI/");
        assertThat(stock(ggn, sku)).isEqualTo(4);
        assertThat(stock(blr, sku)).isEqualTo(5);
    }

    @Test
    void aBengaluruOrderShipsFromBengaluru_withCgstAndSgst_inTheExistingSeries() {
        String sku = product(5, 5);
        String orderId = place(sku, 1, null);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        Order order = awaitInvoice(orderId);
        assertThat(order.getWarehouseId()).isEqualTo(blr);
        assertThat(order.getSellerGstin()).isEqualTo("29AABCO1234F1Z5");
        assertThat(order.getCgstTotal()).isPositive();
        assertThat(order.getIgstTotal()).isZero();
        assertThat(order.getInvoiceNumber()).startsWith("INV/");
    }

    @Test
    void whenTheNearestHasNone_theNextNearestShips() {
        String sku = product(5, 0);
        String orderId = place(sku, 2, delhi());
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        assertThat(order(orderId).getWarehouseId()).isEqualTo(blr);
        assertThat(order(orderId).getSellerGstin()).startsWith("29");
        assertThat(stock(blr, sku)).isEqualTo(3);
    }

    @Test
    void itemsInDifferentWarehousesAreNeverSplit_theCustomerIsAskedToOrderThemSeparately() {
        String inBlr = product(3, 0);
        String inGgn = product(0, 3);
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(line(inBlr, 1), line(inGgn, 1)));
        body.put("shippingAddress", delhi());
        alice().contentType(ContentType.JSON).body(body).post("/api/checkout/quote").then().statusCode(200)
            .body("shipsFrom", nullValue()).body("shippingProblem", containsString("order them separately"));
        alice().contentType(ContentType.JSON).body(body).post("/api/orders").then().statusCode(400)
            .body("reason", containsString("cannot come in one parcel"));
    }

    @Test
    void whenTheChosenWarehouseRunsOutBeforeReserving_theOrderMovesAndItsGstinFollows() {
        String sku = product(5, 2);
        given().post(CONSUMER + "suspend").then().statusCode(200);
        String orderId;
        try {
            orderId = place(sku, 2, delhi());
            assertThat(order(orderId).getWarehouseId()).isEqualTo(ggn);
            assertThat(order(orderId).getSellerGstin()).startsWith("06");
            // Someone else's order, or a write-off, empties Gurugram before this one is reserved
            given().contentType(ContentType.JSON).body(Map.of("type", "WRITE_OFF", "quantity", 2, "reason", "Damaged", "warehouseId", ggn))
                .post("/api/admin/catalog/" + sku + "/stock").then().statusCode(201);
        } finally {
            given().post(CONSUMER + "resume").then().statusCode(200);
        }
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        Order order = order(orderId);
        assertThat(order.getWarehouseId()).isEqualTo(blr);
        assertThat(order.getSellerGstin()).startsWith("29");
        assertThat(order.getIgstTotal()).isPositive();
        assertThat(stock(blr, sku)).isEqualTo(3);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** A product priced ₹999 (18% GST) with this stock in Bengaluru and in Gurugram. */
    private String product(int inBlr, int inGgn) {
        String sku = newSku(0);
        setPrice(sku, 999.00);
        jdbc.update("insert into warehouse_stock (warehouse_id, sku, available_quantity) values (?, ?, ?), (?, ?, ?) "
            + "on conflict (warehouse_id, sku) do update set available_quantity = excluded.available_quantity",
            blr, sku, inBlr, ggn, sku, inGgn);
        jdbc.update("update inventory set available_quantity = ? where sku = ?", inBlr + inGgn, sku);
        return sku;
    }

    private String place(String sku, int quantity, Map<String, Object> address) {
        Map<String, Object> body = new HashMap<>(quote(sku, address));
        body.put("items", List.of(line(sku, quantity)));
        return alice().contentType(ContentType.JSON).body(body).post("/api/orders").then().statusCode(202)
            .extract().path("orderId");
    }

    private static Map<String, Object> quote(String sku, Map<String, Object> address) {
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(line(sku, 1)));
        if (address != null) {
            body.put("shippingAddress", address);
        }
        return body;
    }

    private Order awaitInvoice(String orderId) {
        org.awaitility.Awaitility.await("invoice for " + orderId).atMost(java.time.Duration.ofSeconds(20))
            .until(() -> order(orderId).getInvoiceNumber() != null);
        return order(orderId);
    }

    private int stock(long warehouse, String sku) {
        return jdbc.queryForObject("select available_quantity from warehouse_stock where warehouse_id = ? and sku = ?",
            Integer.class, warehouse, sku);
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private static Map<String, Object> delhi() {
        return Map.of("recipientName", "Alice Smith", "recipientPhone", "+91-98450-00101",
            "line1", "12, Janpath", "city", "New Delhi", "state", "Delhi", "postalCode", "110001", "country", "IN");
    }

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }
}
