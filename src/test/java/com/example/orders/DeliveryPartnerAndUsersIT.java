package com.example.orders;

import com.example.orders.model.OrderStatus;
import com.example.orders.service.DeliveryCodeGenerator;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MinIOContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doReturn;

/**
 * The customer sees who is bringing their order; admins look up any user.
 */
@TestPropertySource(properties = {
        "orders.delivery.simulate=false",
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class DeliveryPartnerAndUsersIT extends IntegrationTestBase {

    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");
    static final String BUCKET = "order-platform-products";
    private static final String CODE = "123456";

    static {
        MINIO.start();
    }

    @MockitoSpyBean DeliveryCodeGenerator codeGenerator;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("orders.storage.endpoint", MINIO::getS3URL);
        registry.add("orders.storage.access-key", MINIO::getUserName);
        registry.add("orders.storage.secret-key", MINIO::getPassword);
        registry.add("orders.storage.bucket", () -> BUCKET);
    }

    @BeforeAll
    static void bucket() throws Exception {
        MinioClient admin = MinioClient.builder().endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        if (!admin.bucketExists(io.minio.BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @BeforeEach
    void fixedCode() {
        doReturn(CODE).when(codeGenerator).next();
    }

    @Test
    void theCustomerSeesTheirDeliveryPartner_fromPickupToDelivery_andNobodyElseDoes() {
        var before = driver().get("/api/me/profile").then().statusCode(200).extract().jsonPath();
        String driverName = before.getString("fullName");
        String driverPhone = before.getString("phone");
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("fullName", "Ravi Kumar Shetty");
            body.put("phone", "9876501234");
            body.put("driver", Map.of("vehicleType", "SCOOTER", "vehicleNumber", "KA05MN4321", "licenceNumber", "KA0520180004321"));
            driver().contentType(ContentType.JSON).body(body).put("/api/me/profile").then().statusCode(200);
            driver().multiPart("file", "me.jpg", CataloguePhotosIT.jpeg(400, 400), "image/jpeg").post("/api/me/photo")
                .then().statusCode(200);

            String sku = newSku(10);
            String orderId = asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
                .post("/api/orders").then().statusCode(202).extract().path("orderId");
            awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);
            String partner = "/api/orders/" + orderId + "/delivery-partner";

            // Taken, but not picked up: the driver may still give it back
            action(orderId, "take");
            alice().get(partner).then().statusCode(200).body("partner", nullValue());
            alice().get(partner + "/photo").then().statusCode(404);

            action(orderId, "pickup");
            String json = alice().get(partner).then().statusCode(200)
                .body("partner.name", equalTo("Ravi S."))
                .body("partner.stage", equalTo("ON_THE_WAY"))
                .body("partner.vehicleType", equalTo("SCOOTER"))
                .body("partner.vehicleNumber", equalTo("KA05MN4321"))
                .body("partner.hasPhoto", equalTo(true))
                .body("partner.deliveries", greaterThanOrEqualTo(0))
                .extract().asString();
            // Never the driver's number, licence or full name
            assertThat(json).doesNotContain("98765").doesNotContain("KA0520180004321").doesNotContain("Shetty");
            alice().get(partner + "/photo").then().statusCode(200).contentType("image/jpeg");

            // Someone else's order does not exist for them; an admin sees it
            asUser(TestTokens.BOB, "CUSTOMER").get(partner).then().statusCode(404);
            asUser(TestTokens.BOB, "CUSTOMER").get(partner + "/photo").then().statusCode(404);
            given().get(partner).then().statusCode(200).body("partner.name", equalTo("Ravi S."));

            action(orderId, "out-for-delivery");
            driver().contentType(ContentType.JSON).body(Map.of("code", CODE))
                .post("/api/delivery/orders/" + orderId + "/deliver").then().statusCode(200);
            alice().get(partner).then().statusCode(200)
                .body("partner.stage", equalTo("DELIVERED"))
                .body("partner.deliveredAt", notNullValue());
        } finally {
            Map<String, Object> body = new HashMap<>();
            body.put("fullName", driverName);
            body.put("phone", driverPhone != null ? driverPhone : "9876501234");
            driver().contentType(ContentType.JSON).body(body).put("/api/me/profile").then().statusCode(200);
            driver().delete("/api/me/photo").then().statusCode(200);
        }
    }

    @Test
    void adminsFindUsers_andSeeTheirProfileAndActivity() {
        String aliceId = alice().get("/api/me").then().extract().path("userId");

        given().get("/api/admin/users?q=alice").then().statusCode(200)
            .body("items.userId", hasItem(aliceId))
            .body("total", greaterThanOrEqualTo(1));
        given().get("/api/admin/users?q=" + aliceId.toLowerCase()).then().statusCode(200)
            .body("items[0].userId", equalTo(aliceId));
        given().get("/api/admin/users?role=DELIVERY&size=100").then().statusCode(200)
            .body("items.roles", everyItem(hasItem("DELIVERY")))
            .body("size", equalTo(100));
        // Wildcards are taken literally
        given().get("/api/admin/users?q=%25").then().statusCode(200).body("total", equalTo(0));

        // An order for Alice, so she has activity
        String sku = newSku(5);
        String orderId = alice().contentType(ContentType.JSON).body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.READY_FOR_DISPATCH);

        given().get("/api/admin/users/" + aliceId).then().statusCode(200)
            .body("profile.userId", equalTo(aliceId))
            .body("profile.customer", notNullValue())
            .body("profile.driver", nullValue())
            .body("customer.orders", greaterThanOrEqualTo(1))
            .body("customer.lastOrderAt", notNullValue())
            .body("driver", nullValue())
            .body("signInLinked", equalTo(true));

        String driverId = driver().get("/api/me").then().extract().path("userId");
        given().get("/api/admin/users/" + driverId).then().statusCode(200)
            .body("profile.driver", notNullValue())
            .body("driver.deliveries", greaterThanOrEqualTo(0))
            .body("driver.earnings", notNullValue())
            .body("customer", nullValue());

        given().get("/api/admin/users/NO-SUCH-USER").then().statusCode(404).body("reason", containsString("No user"));
        alice().get("/api/admin/users").then().statusCode(403);
        driver().get("/api/admin/users/" + aliceId).then().statusCode(403);
        given().get("/api/admin/users?page=0&size=2").then().body("items.size()", equalTo(2))
            .body("items[0].createdAt", matchesPattern("\\d{4}-.*"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private RequestSpecification driver() {
        return asUser(TestTokens.DRIVER_ONE, "DELIVERY");
    }

    private void action(String orderId, String action) {
        driver().contentType(ContentType.JSON).body(Map.of()).post("/api/delivery/orders/" + orderId + "/" + action)
            .then().statusCode(200);
    }
}
