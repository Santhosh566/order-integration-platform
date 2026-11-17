package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * GST from the order request to the stored order, the payment and the invoice.
 * The seller is in Karnataka; the demo customer CUST-100 lives in Bengaluru.
 */
class GstIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void deliveryInTheSellersState_paysCgstAndSgst_includedInThePrice() throws Exception {
        String sku = newSku(10);
        setPrice(sku, 1180.00);

        String orderId = submitOrder(Map.of("customerId", "CUST-100", "items", List.of(Map.of("sku", sku, "quantity", 2))))
            .statusCode(202)
            .body("currency", equalTo("INR"))
            .body("total", equalTo(2360.0f))
            .body("taxTotal", equalTo(360.0f))
            .extract().path("orderId");

        Order order = order(orderId);
        assertThat(order.getPlaceOfSupply()).isEqualTo("29");
        assertThat(order.getSubtotal()).isEqualByComparingTo("2000.00");
        assertThat(order.getCgstTotal()).isEqualByComparingTo("180.00");
        assertThat(order.getSgstTotal()).isEqualByComparingTo("180.00");
        assertThat(order.getIgstTotal()).isEqualByComparingTo("0");
        assertThat(order.getItems().get(0).getProductName()).isEqualTo("Test product");

        // The customer is charged exactly the total, in rupees
        awaitStatus(orderId, OrderStatus.SHIPPED);
        Map<String, Object> charge = jdbcTemplate.queryForMap(
                "select amount, currency from payment_transactions where order_id = ? and kind = 'CHARGE'", orderId);
        assertThat((java.math.BigDecimal) charge.get("amount")).isEqualByComparingTo("2360.00");
        assertThat(charge.get("currency")).isEqualTo("INR");

        // The PDF prints amounts with the currency code, as its font has no rupee sign
        assertThat(invoiceText(orderId)).contains("29AABCO1234F1Z5", "Karnataka (29)",
                "CGST 9% " + com.example.orders.money.Money.formatCode(new java.math.BigDecimal("180.00"), "INR"),
                com.example.orders.money.Money.formatCode(new java.math.BigDecimal("2360.00"), "INR"));
    }

    @Test
    void deliveryToAnotherState_paysIgst() {
        String sku = newSku(10);
        setPrice(sku, 1180.00);

        Map<String, Object> request = new HashMap<>();
        request.put("customerId", "CUST-100");
        request.put("items", List.of(Map.of("sku", sku, "quantity", 1)));
        request.put("shippingAddress", address("Mumbai", "Maharashtra", "400001", "IN"));
        String orderId = submitOrder(request).statusCode(202).extract().path("orderId");

        Order order = order(orderId);
        assertThat(order.getPlaceOfSupply()).isEqualTo("27");
        assertThat(order.getIgstTotal()).isEqualByComparingTo("180.00");
        assertThat(order.getCgstTotal()).isEqualByComparingTo("0");
        assertThat(order.getTotal()).isEqualByComparingTo("1180.00");
    }

    @Test
    void addressesOutsideIndia_orWithABadPinCode_areRefused() {
        String sku = newSku(10);
        for (Map<String, Object> address : List.of(
                address("Boston", "MA", "02101", "US"),
                address("Mumbai", "Maharashtra", "4000", "IN"),
                address("Mumbai", "Gondor", "400001", "IN"))) {
            Map<String, Object> request = new HashMap<>();
            request.put("customerId", "CUST-100");
            request.put("items", List.of(Map.of("sku", sku, "quantity", 1)));
            request.put("shippingAddress", address);
            submitOrder(request).statusCode(400);
        }
    }

    @Test
    void theCatalogueRate_isUsedNotTheCallersPriceOrTax() {
        String sku = newSku(10);
        setPrice(sku, 105.00);
        jdbcTemplate.update("update inventory set gst_rate = 5 where sku = ?", sku);

        String orderId = given().contentType(ContentType.JSON)
            .body(Map.of("customerId", "CUST-100", "items", List.of(Map.of(
                    "sku", sku, "quantity", 1, "price", 0.01, "gstRate", 0, "lineTotal", 0.01, "cgstAmount", 0))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");

        Order order = order(orderId);
        assertThat(order.getTotal()).isEqualByComparingTo("105.00");
        assertThat(order.getItems().get(0).getGstRate()).isEqualByComparingTo("5");
        assertThat(order.getTaxTotal()).isEqualByComparingTo("5.00");
    }

    private static Map<String, Object> address(String city, String state, String pin, String country) {
        Map<String, Object> address = new HashMap<>();
        address.put("recipientName", "Asha Rao");
        address.put("recipientPhone", "+91-98450-00160");
        address.put("line1", "7, MG Road");
        address.put("city", city);
        address.put("state", state);
        address.put("postalCode", pin);
        address.put("country", country);
        return address;
    }
}
