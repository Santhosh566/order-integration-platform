package com.example.orders;

import com.example.orders.config.TimeConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Delivery holidays: an admin keeps the list, and delivery dates skip them where they apply. */
class HolidaysIT extends IntegrationTestBase {

    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.97,77.64
        Maharashtra Circle,Mumbai Region,Mumbai GPO,Mumbai G.P.O,400001,H.O,Delivery,MUMBAI,MAHARASHTRA,18.9,72.8
        """;

    private JsonPath estimate(String sku, String pin) {
        return anonymous().contentType(ContentType.JSON)
            .body(Map.of("query", "query($sku: ID!, $pin: String!) { deliveryEstimate(sku: $sku, pin: $pin) "
                + "{ outcome earliest latest holidays { date name } } }", "variables", Map.of("sku", sku, "pin", pin)))
            .post("/graphql").then().statusCode(200).extract().jsonPath();
    }

    private static Map<String, Object> holiday(LocalDate day, String name, String state) {
        Map<String, Object> m = new HashMap<>();
        m.put("day", day.toString());
        m.put("name", name);
        m.put("stateCode", state);
        return m;
    }

    @Test
    void aStateHolidayMovesDeliveriesThereOnly() {
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200);
        String sku = newSku(10);
        setPrice(sku, 99.0);

        // The first day a Bengaluru order would arrive becomes a Karnataka holiday
        LocalDate first = LocalDate.parse(estimate(sku, "560038").getString("data.deliveryEstimate.earliest"));
        long id = given().contentType(ContentType.JSON).body(holiday(first, "Test festival", "KA"))
            .post("/api/admin/holidays").then().statusCode(201)
            .body("stateCode", equalTo("29")).body("stateName", equalTo("Karnataka"))
            .extract().jsonPath().getLong("id");
        try {
            JsonPath bengaluru = estimate(sku, "560038");
            assertThat(LocalDate.parse(bengaluru.getString("data.deliveryEstimate.earliest"))).isAfter(first);
            assertThat(bengaluru.getList("data.deliveryEstimate.holidays.name")).contains("Test festival");
            // Mumbai still delivers that day
            assertThat(estimate(sku, "400001").getList("data.deliveryEstimate.holidays.name")).doesNotContain("Test festival");

            assertThat(given().get("/api/admin/holidays?year=" + first.getYear()).then().statusCode(200)
                .extract().jsonPath().getList("name")).contains("Test festival");
            // Once per day and state
            given().contentType(ContentType.JSON).body(holiday(first, "Again", "Karnataka"))
                .post("/api/admin/holidays").then().statusCode(400);
        } finally {
            given().delete("/api/admin/holidays/" + id).then().statusCode(204);
        }
        assertThat(estimate(sku, "560038").getString("data.deliveryEstimate.earliest")).isEqualTo(first.toString());
        given().delete("/api/admin/holidays/" + id).then().statusCode(404);
    }

    @Test
    void theNationalHolidaysAreThere_andMistakesAreRefused() {
        assertThat(given().get("/api/admin/holidays?year=2027").then().statusCode(200).extract().jsonPath()
            .getList("name")).containsExactly("Republic Day", "Independence Day", "Gandhi Jayanti");
        given().get("/api/admin/holidays?year=2027").then().body("[0].stateCode", nullValue());

        LocalDate today = LocalDate.now(TimeConfig.INDIA);
        LocalDate sunday = today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.SUNDAY));
        given().contentType(ContentType.JSON).body(holiday(sunday, "Sunday", null))
            .post("/api/admin/holidays").then().statusCode(400);
        given().contentType(ContentType.JSON).body(holiday(sunday.plusDays(1), "Somewhere", "ATLANTIS"))
            .post("/api/admin/holidays").then().statusCode(400);
        given().contentType(ContentType.JSON).body(holiday(sunday.plusDays(1), " ", null))
            .post("/api/admin/holidays").then().statusCode(400);

        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/admin/holidays").then().statusCode(403);
    }
}
