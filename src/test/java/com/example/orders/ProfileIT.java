package com.example.orders;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Each user's own profile: name and mobile for everyone, a photo, and the part for their role.
 * Names and numbers are put back afterwards, since other tests share these users.
 */
class ProfileIT extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");
    static final String BUCKET = "order-platform-products";

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("orders.storage.endpoint", MINIO::getS3URL);
        registry.add("orders.storage.access-key", MINIO::getUserName);
        registry.add("orders.storage.secret-key", MINIO::getPassword);
        registry.add("orders.storage.bucket", () -> BUCKET);
    }

    @BeforeAll
    static void bucket() throws Exception {
        MinioClient admin = minio();
        if (!admin.bucketExists(io.minio.BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void aCustomerSavesTheirDetails_andOnlyTheCustomerPartApplies() {
        RequestSpecification alice = asUser(TestTokens.ALICE, "CUSTOMER");
        var before = alice.get("/api/me/profile").then().statusCode(200)
            .body("customer", notNullValue())
            .body("driver", nullValue())
            .body("staff", nullValue())
            .body("hasPhoto", equalTo(false))
            .extract().jsonPath();
        String name = before.getString("fullName");
        String phone = before.getString("phone");
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("fullName", "  Alice   Smith-Rao ");
            body.put("phone", "98450 00101");
            body.put("customer", Map.of("dateOfBirth", "1990-04-12", "gender", "FEMALE"));
            body.put("driver", Map.of("vehicleType", "BIKE"));
            asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(body).put("/api/me/profile").then()
                .statusCode(200)
                .body("fullName", equalTo("Alice Smith-Rao"))
                .body("phone", equalTo("+91-98450-00101"))
                .body("customer.dateOfBirth", equalTo("1990-04-12"))
                .body("customer.gender", equalTo("FEMALE"))
                .body("driver", nullValue());
            asUser(TestTokens.ALICE, "CUSTOMER").get("/api/me").then()
                .body("fullName", equalTo("Alice Smith-Rao")).body("phone", equalTo("+91-98450-00101"));

            // Under 18, a bad number, an empty name
            save(TestTokens.ALICE, "CUSTOMER", "Alice", null,
                Map.of("customer", Map.of("dateOfBirth", java.time.LocalDate.now().minusYears(15).toString())))
                .statusCode(400).body("reason", containsString("at least 18"));
            save(TestTokens.ALICE, "CUSTOMER", "Alice", "12345", Map.of())
                .statusCode(400).body("reason", containsString("10-digit Indian mobile"));
            save(TestTokens.ALICE, "CUSTOMER", " ", null, Map.of()).statusCode(400);
        } finally {
            save(TestTokens.ALICE, "CUSTOMER", name, phone, Map.of("customer", Map.of())).statusCode(200);
        }
    }

    @Test
    void aDriverNeedsVehicleAndLicenceDetails_forAMotorisedVehicle() {
        var before = asUser(TestTokens.DRIVER_TWO, "DELIVERY").get("/api/me/profile").then().statusCode(200)
            .body("driver", notNullValue()).body("customer", nullValue()).extract().jsonPath();
        String name = before.getString("fullName");
        String phone = before.getString("phone");
        try {
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, null, Map.of("driver", Map.of("vehicleType", "CYCLE")))
                .statusCode(400).body("reason", containsString("Mobile number is required"));
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, "9876543210", Map.of("driver", Map.of("vehicleType", "BIKE")))
                .statusCode(400).body("reason", containsString("Vehicle number"));
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, "9876543210",
                Map.of("driver", Map.of("vehicleType", "BIKE", "vehicleNumber", "KA 01 AB 1234", "licenceNumber", "KA01 2019 0001")))
                .statusCode(400).body("reason", containsString("Driving licence"));
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, "9876543210",
                Map.of("driver", Map.of("vehicleType", "BIKE", "vehicleNumber", "KA01AB1234", "licenceNumber", "KA0120190001234",
                    "emergencyContactName", "Ravi", "emergencyContactPhone", "+91 98765 43210")))
                .statusCode(400).body("reason", containsString("other than your own"));
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, "9876543210",
                Map.of("driver", Map.of("vehicleType", "BIKE", "vehicleNumber", "KA01AB1234", "licenceNumber", "KA0120190001234",
                    "upiId", "not-a-upi")))
                .statusCode(400).body("reason", containsString("UPI"));

            save(TestTokens.DRIVER_TWO, "DELIVERY", name, "9876543210",
                Map.of("driver", Map.of("vehicleType", "EV_SCOOTER", "vehicleNumber", "ka-01-ab-1234",
                    "licenceNumber", "ka01 20190001234", "emergencyContactName", "Ravi Kumar",
                    "emergencyContactPhone", "08123456789", "upiId", "Ravi.Driver@okicici")))
                .statusCode(200)
                .body("driver.vehicleNumber", equalTo("KA01AB1234"))
                .body("driver.licenceNumber", equalTo("KA0120190001234"))
                .body("driver.emergencyContactPhone", equalTo("+91-81234-56789"))
                .body("driver.upiId", equalTo("ravi.driver@okicici"));

            // On a cycle neither is needed, and old ones are dropped
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, "9876543210",
                Map.of("driver", Map.of("vehicleType", "CYCLE", "vehicleNumber", "KA01AB1234")))
                .statusCode(200)
                .body("driver.vehicleType", equalTo("CYCLE"))
                .body("driver.vehicleNumber", nullValue())
                .body("driver.licenceNumber", nullValue());
        } finally {
            save(TestTokens.DRIVER_TWO, "DELIVERY", name, phone, Map.of()).statusCode(200);
        }
    }

    @Test
    void anAdminHasAStaffPart() {
        var before = given().get("/api/me/profile").then().statusCode(200)
            .body("staff", notNullValue()).extract().jsonPath();
        String name = before.getString("fullName");
        try {
            save(TestTokens.ADMIN, "ADMIN", name, null, Map.of("staff", Map.of("jobTitle", "Operations lead", "department", "Fulfilment")))
                .statusCode(200)
                .body("staff.jobTitle", equalTo("Operations lead"))
                .body("staff.department", equalTo("Fulfilment"));
        } finally {
            save(TestTokens.ADMIN, "ADMIN", name, before.getString("phone"), Map.of("staff", Map.of())).statusCode(200);
        }
    }

    @Test
    void aPhotoIsCroppedSquare_privateToItsOwnerAndAdmins_andReplacedCleanly() throws Exception {
        RequestSpecification bob = asUser(TestTokens.BOB, "CUSTOMER");
        String bobId = bob.get("/api/me").then().extract().path("userId");

        asUser(TestTokens.BOB, "CUSTOMER").multiPart("file", "me.jpg", CataloguePhotosIT.jpeg(800, 400), "image/jpeg")
            .post("/api/me/photo").then().statusCode(200)
            .body("hasPhoto", equalTo(true))
            .body("photoUpdatedAt", notNullValue());
        String firstKey = photoKey(bobId);
        assertThat(firstKey).startsWith("profiles/" + bobId + "/").endsWith("-256.jpg");
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/me").then().body("photoUpdatedAt", notNullValue());

        byte[] served = asUser(TestTokens.BOB, "CUSTOMER").get("/api/users/" + bobId + "/photo").then().statusCode(200)
            .contentType("image/jpeg")
            .header("Cache-Control", containsString("private"))
            .extract().asByteArray();
        BufferedImage square = ImageIO.read(new ByteArrayInputStream(served));
        assertThat(square.getWidth()).isEqualTo(256);
        assertThat(square.getHeight()).isEqualTo(256);

        // Admins may see it; other customers and drivers may not, and cannot tell it exists
        given().get("/api/users/" + bobId + "/photo").then().statusCode(200);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/users/" + bobId + "/photo").then().statusCode(404);
        asUser(TestTokens.DRIVER_ONE, "DELIVERY").get("/api/users/" + bobId + "/photo").then().statusCode(404);
        anonymous().get("/api/users/" + bobId + "/photo").then().statusCode(401);

        // Not a picture, too small
        asUser(TestTokens.BOB, "CUSTOMER").multiPart("file", "x.txt", "hello".getBytes(), "text/plain")
            .post("/api/me/photo").then().statusCode(400);
        asUser(TestTokens.BOB, "CUSTOMER").multiPart("file", "tiny.jpg", CataloguePhotosIT.jpeg(100, 100), "image/jpeg")
            .post("/api/me/photo").then().statusCode(400).body("reason", containsString("too small"));

        // A new photo replaces the file; removing it removes the file too
        asUser(TestTokens.BOB, "CUSTOMER").multiPart("file", "me2.jpg", CataloguePhotosIT.jpeg(300, 300), "image/jpeg")
            .post("/api/me/photo").then().statusCode(200);
        String secondKey = photoKey(bobId);
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertGone(firstKey);

        asUser(TestTokens.BOB, "CUSTOMER").delete("/api/me/photo").then().statusCode(200).body("hasPhoto", equalTo(false));
        assertGone(secondKey);
        asUser(TestTokens.BOB, "CUSTOMER").get("/api/users/" + bobId + "/photo").then().statusCode(404);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private io.restassured.response.ValidatableResponse save(String subject, String role, String name, String phone,
                                                            Map<String, Object> parts) {
        Map<String, Object> body = new HashMap<>(parts);
        body.put("fullName", name);
        body.put("phone", phone);
        return asUser(subject, role).contentType(ContentType.JSON).body(body).put("/api/me/profile").then();
    }

    private String photoKey(String userId) {
        return jdbc().queryForObject("select photo_key from user_profiles where user_id = ?", String.class, userId);
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbc;
    }

    private static void assertGone(String key) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
            assertThatThrownBy(() -> minio().statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build()))
                .hasMessageContaining("does not exist"));
    }

    private static MinioClient minio() {
        return MinioClient.builder().endpoint(MINIO.getS3URL()).credentials(MINIO.getUserName(), MINIO.getPassword()).build();
    }
}
