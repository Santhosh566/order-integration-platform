package com.example.orders;

import com.example.orders.delivery.LiveTrackingService;
import com.example.orders.model.OrderStatus;
import com.example.orders.service.DeliveryCodeGenerator;
import com.example.orders.service.OrderEventBroadcaster;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class LiveTrackingIT extends IntegrationTestBase {

    private static final String CODE = "123456";

    // Two post offices for Alice's PIN (median 12.97, 77.64) and one with no position
    private static final String DIRECTORY = """
        circlename,regionname,divisionname,officename,pincode,officetype,delivery,district,statename,latitude,longitude
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,Indiranagar S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.9700,77.6400
        Karnataka Circle,Bangalore HQ Region,Bangalore East Division,HAL II Stage S.O,560038,S.O,Delivery,BENGALURU URBAN,KARNATAKA,12.9700,77.6400
        Karnataka Circle,Bangalore HQ Region,Bangalore GPO Division,Bangalore G.P.O.,560001,H.O,Delivery,BENGALURU URBAN,KARNATAKA,NA,NA
        """;

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;
    @MockitoSpyBean OrderEventBroadcaster broadcaster;
    @Autowired LiveTrackingService tracking;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        doReturn(CODE).when(codeGenerator).next();
        given().multiPart("file", "pincode.csv", DIRECTORY.getBytes(StandardCharsets.UTF_8), "text/csv")
            .post("/api/admin/pin-codes").then().statusCode(200);
        // Each test starts without a last position, so none is held back by the 10-second rule
        jdbc.update("delete from driver_locations");
    }

    @Test
    void theCustomerSeesTheDriverOnlyWhileTheParcelIsOutForDelivery() {
        String orderId = readyOrder();
        alice().get(trackingOf(orderId)).then().statusCode(200)
            .body("live", equalTo(false)).body("message", containsString("starts when your order is out for delivery"));

        // Not out for delivery yet: the phone's position is refused
        send(driverOne(), 12.95, 77.62).statusCode(409);
        action(orderId, "take");
        action(orderId, "pickup");
        send(driverOne(), 12.95, 77.62).statusCode(409);

        action(orderId, "out-for-delivery");
        alice().get(trackingOf(orderId)).then().statusCode(200)
            .body("live", equalTo(true))
            .body("driver", nullValue())
            .body("destination.latitude", equalTo(12.97f))
            .body("destination.approximate", equalTo(true));

        // About 4.2 km away in a straight line: 5.9 km by road, 18 minutes at 20 km/h
        send(driverOne(), 12.9352, 77.6245).statusCode(200).body("stored", equalTo(true)).body("sendEverySeconds", equalTo(10));
        JsonPath map = alice().get(trackingOf(orderId)).then().statusCode(200).extract().jsonPath();
        assertThat(map.getDouble("driver.latitude")).isEqualTo(12.9352);
        assertThat(map.getBoolean("driver.stale")).isFalse();
        assertThat(map.getDouble("distanceKm")).isBetween(5.8, 6.0);
        assertThat(map.getInt("etaMinutes")).isEqualTo(18);
        assertThat(map.getString("driverName")).isNotBlank();
        verify(broadcaster, atLeastOnce()).broadcastForOrder(eq(LiveTrackingService.EVENT), eq(orderId), eq("CUST-100"), any(), any());

        // Too soon after the last one: answered, not kept
        send(driverOne(), 12.94, 77.63).statusCode(200).body("stored", equalTo(false));
        assertThat(alice().get(trackingOf(orderId)).then().extract().jsonPath().getDouble("driver.latitude")).isEqualTo(12.9352);

        // Only Alice and the admins see it
        asUser(TestTokens.BOB, "CUSTOMER").get(trackingOf(orderId)).then().statusCode(404);
        given().get(trackingOf(orderId)).then().statusCode(200).body("live", equalTo(true));
        given().get("/api/admin/delivery/locations").then().statusCode(200)
            .body("find { it.orderIds.contains('" + orderId + "') }.position.latitude", equalTo(12.9352f));
        alice().get("/api/admin/delivery/locations").then().statusCode(403);

        // Within a kilometre of the centre of Alice's PIN: told once that the driver is in her area
        assertThat(map.getBoolean("inYourArea")).isFalse();
        assertThat(areaEmails(orderId)).isZero();
        jdbc.update("update driver_locations set received_at = now() - interval '1 minute'");
        send(driverOne(), 12.9720, 77.6410).statusCode(200).body("stored", equalTo(true));
        alice().get(trackingOf(orderId)).then().body("inYourArea", equalTo(true));
        assertThat(areaEmails(orderId)).isEqualTo(1);
        jdbc.update("update driver_locations set received_at = now() - interval '1 minute'");
        send(driverOne(), 12.9705, 77.6402).statusCode(200).body("stored", equalTo(true));
        assertThat(areaEmails(orderId)).as("only once per attempt").isEqualTo(1);

        // Delivered: tracking ends, and the phone stops being asked for positions
        action(orderId, "deliver", Map.of("code", CODE));
        alice().get(trackingOf(orderId)).then().statusCode(200)
            .body("live", equalTo(false)).body("driver", nullValue()).body("message", containsString("Delivered"));
        send(driverOne(), 12.94, 77.63).statusCode(409);
    }

    @Test
    void positionsOutsideIndia_orTooRough_areRefused() {
        String orderId = readyOrder();
        action(orderId, "take");
        action(orderId, "pickup");
        action(orderId, "out-for-delivery");

        // DevTools' default sensor is London
        send(driverOne(), 51.5074, -0.1278).statusCode(400).body("reason", containsString("outside India"));
        Map<String, Object> rough = new HashMap<>(Map.of("latitude", 12.95, "longitude", 77.62, "accuracyM", 5000));
        driverOne().contentType(ContentType.JSON).body(rough).post("/api/delivery/location").then()
            .statusCode(400).body("reason", containsString("too rough"));
        // Customers cannot send positions
        alice().contentType(ContentType.JSON).body(Map.of("latitude", 12.95, "longitude", 77.62))
            .post("/api/delivery/location").then().statusCode(403);

        action(orderId, "deliver", Map.of("code", CODE));
    }

    @Test
    void theTrailIsDeletedAfterAWeek() {
        String driver = jdbc.queryForObject("select user_id from app_users where keycloak_subject = ?", String.class, TestTokens.DRIVER_ONE);
        jdbc.update("insert into driver_location_trail (driver_id, location, recorded_at, received_at) values "
            + "(?, ST_MakePoint(77.6, 12.9)::geography, now() - interval '8 days', now() - interval '8 days'), "
            + "(?, ST_MakePoint(77.6, 12.9)::geography, now(), now())", driver, driver);
        int before = jdbc.queryForObject("select count(*) from driver_location_trail where driver_id = ?", Integer.class, driver);

        tracking.cleanUp();

        assertThat(jdbc.queryForObject("select count(*) from driver_location_trail where driver_id = ?", Integer.class, driver))
            .isEqualTo(before - 1);
        assertThat(jdbc.queryForObject("select count(*) from driver_location_trail where received_at < now() - interval '7 days'",
            Integer.class)).isZero();
    }

    @Test
    void theDirectoryKeepsTheMedianPositionOfEachPin_asAPostgisPoint() {
        assertThat(jdbc.queryForMap("select ST_Y(centre::geometry) as lat, ST_X(centre::geometry) as lng, "
                + "ST_AsText(centre) as wkt from pin_codes where pin = '560038'"))
            .satisfies(row -> {
                assertThat(((Number) row.get("lat")).doubleValue()).isEqualTo(12.97);
                assertThat(((Number) row.get("lng")).doubleValue()).isEqualTo(77.64);
                // Longitude first, as PostGIS writes points
                assertThat(row.get("wkt")).isEqualTo("POINT(77.64 12.97)");
            });
        assertThat(jdbc.queryForObject("select centre is null from pin_codes where pin = '560001'", Boolean.class)).isTrue();
        // Real metres on the globe: Indiranagar to Bangalore GPO is about 4.9 km
        assertThat(jdbc.queryForObject("select ST_Distance(ST_MakePoint(77.64, 12.97)::geography, "
                + "ST_MakePoint(77.5946, 12.9716)::geography)", Double.class)).isBetween(4_850.0, 5_000.0);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String readyOrder() {
        String orderId = placeOrder(newSku(10), 1, 25.0);
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
        return orderId;
    }

    private int areaEmails(String orderId) {
        return jdbc.queryForObject("select count(*) from customer_notifications where order_id = ? and subject like '%is in your area%'",
            Integer.class, orderId);
    }

    private static String trackingOf(String orderId) {
        return "/api/orders/" + orderId + "/tracking";
    }

    private ValidatableResponse send(RequestSpecification who, double latitude, double longitude) {
        return who.contentType(ContentType.JSON).body(Map.of("latitude", latitude, "longitude", longitude, "accuracyM", 15))
            .post("/api/delivery/location").then();
    }

    private void action(String orderId, String action) {
        action(orderId, action, Map.of());
    }

    private void action(String orderId, String action, Map<String, ?> body) {
        driverOne().contentType(ContentType.JSON).body(body).post("/api/delivery/orders/" + orderId + "/" + action)
            .then().statusCode(200);
    }

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification driverOne() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }
}
