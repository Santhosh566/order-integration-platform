package com.example.orders;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** Delivery dates by PIN, the per-order limit, and product links that preview well when shared. */
class ShopPageFeaturesIT extends IntegrationTestBase {

    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.97,77.64
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,HAL II Stage S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.96,77.64
        North Eastern Circle,Manipur Region,Imphal Division,Imphal H.O,795001,H.O,Delivery,IMPHAL WEST,MANIPUR,24.8,93.9
        "Gujarat Circle",Vadodara Region,Daman Division,"Daman, Moti S.O",396210,S.O,Delivery,DAMAN,THE DADRA AND NAGAR HAVELI AND DAMAN AND DIU,20.4,72.8
        Unknown Circle,X,Y,Z,123456,S.O,Delivery,NOWHERE,ATLANTIS,0,0
        """;

    private JsonPath estimate(String sku, String pin) {
        return anonymous().contentType(ContentType.JSON)
            .body(Map.of("query", "query($sku: ID!, $pin: String!) { deliveryEstimate(sku: $sku, pin: $pin) "
                + "{ outcome district state earliest latest approximate minutesToCutOff } }",
                "variables", Map.of("sku", sku, "pin", pin)))
            .post("/graphql").then().statusCode(200).extract().jsonPath();
    }

    @Test
    void theIndiaPostDirectoryIsImported_andGivesDeliveryDatesByPin() {
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200)
            .body("pins", equalTo(3)).body("rowsRead", equalTo(5)).body("unknownStates", hasItem("ATLANTIS"));
        asUser(TestTokens.ALICE, "CUSTOMER").multiPart("file", "x.csv", "a".getBytes(), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(403);

        String sku = newSku(10);
        setPrice(sku, 99.0);
        JsonPath bengaluru = estimate(sku, "560038");
        assertThat(bengaluru.getString("data.deliveryEstimate.outcome")).isEqualTo("DELIVERABLE");
        assertThat(bengaluru.getString("data.deliveryEstimate.district")).isEqualTo("Bengaluru Urban");
        assertThat(bengaluru.getBoolean("data.deliveryEstimate.approximate")).isFalse();
        assertThat(bengaluru.getString("data.deliveryEstimate.earliest")).matches("\\d{4}-\\d{2}-\\d{2}");

        assertThat(estimate(sku, "396210").getString("data.deliveryEstimate.state"))
            .isEqualTo("Dadra and Nagar Haveli and Daman and Diu");
        assertThat(estimate(sku, "999999").getString("data.deliveryEstimate.outcome")).isEqualTo("UNKNOWN_PIN");
        assertThat(estimate(sku, "12ab").getString("data.deliveryEstimate.outcome")).isEqualTo("INVALID_PIN");
        assertThat(estimate(newSku(0), "560038").getString("data.deliveryEstimate.outcome")).isIn("OUT_OF_STOCK");
    }

    @Test
    void aCustomerCannotOrderMoreThanTheLimit_butStaffCan() {
        String sku = newSku(20);
        setPrice(sku, 50.0);
        int version = given().get("/api/admin/catalog/" + sku).then().extract().path("product.version");
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", version, "maxPerOrder", 2))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200).body("maxPerOrder", equalTo(2));

        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 2), Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(400).body("reason", containsString("At most 2"));
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 2))))
            .post("/api/orders").then().statusCode(202);
        submitOrder(Map.of("customerId", "CUST-100", "items", List.of(Map.of("sku", sku, "quantity", 5))))
            .statusCode(202);
    }

    @Test
    void aSharedProductLinkCarriesItsPreview_safelyEscaped() {
        String sku = newSku(10);
        setPrice(sku, 1499.0);
        int version = given().get("/api/admin/catalog/" + sku).then().extract().path("product.version");
        given().contentType(ContentType.JSON)
            .body(Map.of("expectedVersion", version, "name", "Lamp \"Deluxe\" <script>alert(1)</script>",
                         "description", "Warm light.\n\nSecond paragraph."))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200);

        anonymous().get("/shop/" + sku).then().statusCode(200)
            .header("Cache-Control", containsString("no-cache"))
            .body(containsString("property=\"og:title\" content=\"Lamp &quot;Deluxe&quot; &lt;script&gt;"))
            .body(containsString("og:description\" content=\"Warm light.\""))
            .body(not(containsString("<script>alert(1)")))
            .body(containsString("<div id=\"root\">"));
        // Not a product: the app as usual
        anonymous().get("/shop/NO-SUCH").then().statusCode(200).body(not(containsString("og:title")));
    }

    @Test
    void thumbnailsNameEveryProductAskedFor_soldOrNot() {
        String onSale = newSku(5);
        String retired = newSku(5);
        var item = inventoryRepository.findById(retired).orElseThrow();
        item.setProductName("Old lamp");
        item.setActive(false);
        inventoryRepository.save(item);

        anonymous().queryParam("sku", onSale, retired, "NO-SUCH").get("/api/public/thumbnails").then().statusCode(200)
            .body("size()", equalTo(2))
            .body("find { it.sku == '" + retired + "' }.name", equalTo("Old lamp"))
            .body("find { it.sku == '" + onSale + "' }.imageKey", org.hamcrest.Matchers.nullValue());
        anonymous().get("/api/public/thumbnails").then().statusCode(200).body("size()", equalTo(0));
    }
}
