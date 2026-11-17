package com.example.orders;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Coupons for chosen products or categories, the offers the shop shows on products, and promotion
 * banners that point at a product, a category or a coupon.
 */
class PromotionsIT extends IntegrationTestBase {

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
        MinioClient admin = MinioClient.builder().endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        if (!admin.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            admin.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @Test
    void aCouponForSomeProducts_takesMoneyOffOnlyThem_andTheShopShowsItOnThem() {
        long lamps = category("Test lamps " + suffix(), null);
        long desk = category("Desk lamps " + suffix(), lamps);
        String lamp = product(1000.00, desk);     // in a sub-category of the coupon's category
        String mug = product(300.00, null);       // named on the coupon
        String pen = product(500.00, null);       // not on the coupon
        String code = coupon(Map.of("discountType", "PERCENT", "discountValue", 20, "minOrderValue", 500,
            "categoryIds", List.of(lamps), "skus", List.of(mug)));

        given().get("/api/admin/coupons").then().statusCode(200)
            .body("find { it.code == '" + code + "' }.appliesTo.name", hasItem(containsString("Test lamps")))
            .body("find { it.code == '" + code + "' }.appliesTo.sku", hasItem(mug));

        // 20% of the lamp only: the pen pays full price
        JsonPath quote = quote(List.of(line(lamp, 1), line(pen, 1)), code);
        assertThat(quote.getString("couponError")).isNull();
        assertThat(quote.<BigDecimal>get("discount")).isEqualByComparingTo("200.00");
        assertThat(quote.<BigDecimal>get("items.find { it.sku == '" + lamp + "' }.discount")).isEqualByComparingTo("200.00");
        assertThat(quote.<BigDecimal>get("items.find { it.sku == '" + pen + "' }.discount")).isEqualByComparingTo("0");
        assertThat(quote.<BigDecimal>get("total")).isEqualByComparingTo("1300.00");

        // The minimum counts only the products it is for: ₹800 of pens does not reach it
        assertThat(quote(List.of(line(pen, 2)), code).getString("couponError")).contains("none of them is in your cart");
        assertThat(quote(List.of(line(mug, 1), line(pen, 2)), code).getString("couponError"))
            .contains("needs at least ₹500").contains("add ₹200");
        JsonPath two = quote(List.of(line(mug, 2), line(pen, 1)), code);
        assertThat(two.<BigDecimal>get("discount")).isEqualByComparingTo("120.00");

        // The shop shows it on the lamp and the mug, not on the pen; the offer filter lists them
        JsonPath offers = graphql("query($a: ID!, $b: ID!) { a: product(sku: $a) { offers { code targeted discountValue } } "
            + "b: product(sku: $b) { offers { code } } }", Map.of("a", lamp, "b", pen));
        assertThat(offers.getList("data.a.offers.code")).contains(code);
        assertThat(offers.<Boolean>get("data.a.offers.find { it.code == '" + code + "' }.targeted")).isTrue();
        assertThat(offers.getList("data.b.offers.code")).doesNotContain(code);
        JsonPath listed = graphql("query($f: ProductFilter) { products(filter: $f, size: 100) { items { sku } } }",
            Map.of("f", Map.of("offer", code.toLowerCase())));
        assertThat(listed.getList("data.products.items.sku")).contains(lamp, mug).doesNotContain(pen);
        assertThat(graphql("query($f: ProductFilter) { products(filter: $f) { totalItems } }",
            Map.of("f", Map.of("offer", "NOSUCHCODE"))).getInt("data.products.totalItems")).isZero();

        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/checkout/offers").then().statusCode(200)
            .body("find { it.code == '" + code + "' }.appliesTo", hasItem(containsString("Test lamps")));

        // Retargeted to the pen: the lamp no longer counts
        given().contentType(ContentType.JSON).body(Map.of("skus", List.of(pen), "categoryIds", List.of()))
            .patch("/api/admin/coupons/" + code).then().statusCode(200).body("appliesTo.sku", equalTo(List.of(pen)));
        JsonPath after = quote(List.of(line(lamp, 1), line(pen, 1)), code);
        assertThat(after.<BigDecimal>get("items.find { it.sku == '" + pen + "' }.discount")).isEqualByComparingTo("100.00");
        assertThat(after.<BigDecimal>get("items.find { it.sku == '" + lamp + "' }.discount")).isEqualByComparingTo("0");

        given().contentType(ContentType.JSON).body(Map.of("skus", List.of("NO-SUCH-SKU")))
            .patch("/api/admin/coupons/" + code).then().statusCode(400).body("reason", containsString("No product NO-SUCH-SKU"));
    }

    @Test
    void bannersShowWhileWhatTheyAdvertiseCanBeBought_andCountViewsAndClicks() throws Exception {
        long gifts = category("Test gifts " + suffix(), null);
        String watch = product(2499.00, gifts);
        String code = coupon(Map.of("discountType", "PERCENT", "discountValue", 15, "categoryIds", List.of(gifts)));

        long forCoupon = promotion(Map.of("title", "15% off gifts", "targetType", "COUPON", "couponCode", code.toLowerCase(),
            "theme", "GRAPE", "callToAction", "Shop gifts", "priority", 100));
        long forProduct = promotion(Map.of("title", "The new watch", "targetType", "PRODUCT", "sku", watch, "priority", 99));
        long forCategory = promotion(Map.of("title", "Gifts for everyone", "targetType", "CATEGORY", "categoryId", gifts,
            "priority", 98));
        long later = promotion(Map.of("title", "Coming soon", "targetType", "PRODUCT", "sku", watch, "priority", 97,
            "startsAt", LocalDateTime.now().plusDays(2).withNano(0).toString()));

        // Anyone sees the live ones; the coupon banner carries its terms
        anonymous().get("/api/public/promotions").then().statusCode(200)
            .body("id", hasItem((int) forCoupon)).body("id", hasItem((int) forProduct)).body("id", hasItem((int) forCategory))
            .body("id", not(hasItem((int) later)))
            .body("find { it.id == " + forCoupon + " }.couponCode", equalTo(code))
            .body("find { it.id == " + forCoupon + " }.offer.discountValue", equalTo(15.0f))
            .body("find { it.id == " + forCoupon + " }.appliesTo", hasItem(containsString("Test gifts")))
            .body("find { it.id == " + forProduct + " }.productName", equalTo("Test product"))
            .body("find { it.id == " + forCategory + " }.categorySlug", startsWith("test-gifts"));
        given().get("/api/admin/promotions/" + later).then().body("status", equalTo("SCHEDULED"));

        // A wide picture, cut to 3:1 and served like product photos; a tall one is refused
        String key = given().multiPart("file", "banner.jpg", CataloguePhotosIT.jpeg(1800, 700), "image/jpeg")
            .post("/api/admin/promotions/" + forProduct + "/image").then().statusCode(200)
            .body("imageKey", startsWith("promotions/" + forProduct + "/")).extract().path("imageKey");
        anonymous().get("/api/public/images/" + key).then().statusCode(200).contentType("image/jpeg");
        anonymous().get("/api/public/images/promotions/" + forProduct + "/made-up.jpg").then().statusCode(404);
        given().multiPart("file", "tall.jpg", CataloguePhotosIT.jpeg(400, 900), "image/jpeg")
            .post("/api/admin/promotions/" + forProduct + "/image").then().statusCode(400)
            .body("reason", containsString("wider than it is tall"));
        String second = given().multiPart("file", "banner2.jpg", CataloguePhotosIT.jpeg(1500, 500), "image/jpeg")
            .post("/api/admin/promotions/" + forProduct + "/image").then().statusCode(200).extract().path("imageKey");
        assertThat(second).isNotEqualTo(key);
        anonymous().get("/api/public/images/" + key).then().statusCode(404);

        // Shoppers' views and clicks count; staff browsing does not
        RequestSpecification alice = asUser(TestTokens.ALICE, "CUSTOMER");
        alice.post("/api/promotions/" + forCoupon + "/view").then().statusCode(204);
        asUser(TestTokens.ALICE, "CUSTOMER").post("/api/promotions/" + forCoupon + "/view").then().statusCode(204);
        asUser(TestTokens.ALICE, "CUSTOMER").post("/api/promotions/" + forCoupon + "/click").then().statusCode(204);
        given().post("/api/promotions/" + forCoupon + "/click").then().statusCode(403);
        asUser(TestTokens.ALICE, "CUSTOMER").post("/api/promotions/999999/click").then().statusCode(404);
        given().get("/api/admin/promotions/" + forCoupon).then().statusCode(200)
            .body("impressions", equalTo(2)).body("clicks", equalTo(1)).body("status", equalTo("LIVE"));

        // What cannot be bought is not advertised
        var item = inventoryRepository.findById(watch).orElseThrow();
        item.setActive(false);
        inventoryRepository.save(item);
        given().get("/api/admin/promotions/" + forProduct).then()
            .body("status", equalTo("UNAVAILABLE")).body("unavailableReason", containsString("not on sale"));
        given().contentType(ContentType.JSON).body(Map.of("active", false)).patch("/api/admin/coupons/" + code)
            .then().statusCode(200);
        given().get("/api/admin/promotions/" + forCoupon).then().body("status", equalTo("UNAVAILABLE"));
        anonymous().get("/api/public/promotions").then()
            .body("id", not(hasItem((int) forProduct))).body("id", not(hasItem((int) forCoupon)))
            .body("id", hasItem((int) forCategory));

        // Off, changed, deleted
        given().contentType(ContentType.JSON).body(Map.of("active", false)).patch("/api/admin/promotions/" + forCategory)
            .then().statusCode(200).body("status", equalTo("OFF"));
        Map<String, Object> edit = new HashMap<>(Map.of("title", "Gifts, now 3 for 2", "targetType", "CATEGORY",
            "categoryId", gifts, "message", "Ends Sunday", "active", true));
        given().contentType(ContentType.JSON).body(edit).put("/api/admin/promotions/" + forCategory).then().statusCode(200)
            .body("title", equalTo("Gifts, now 3 for 2")).body("message", equalTo("Ends Sunday"))
            .body("status", equalTo("LIVE")).body("priority", equalTo(0)).body("imageKey", nullValue());
        given().delete("/api/admin/promotions/" + forProduct).then().statusCode(204);
        given().get("/api/admin/promotions/" + forProduct).then().statusCode(404);
        anonymous().get("/api/public/images/" + second).then().statusCode(404);
        for (long id : List.of(forCoupon, forCategory, later)) {
            given().delete("/api/admin/promotions/" + id).then().statusCode(204);
        }
    }

    @Test
    void aPromotionMustPointAtSomethingReal() {
        bad(Map.of("title", "x", "targetType", "PRODUCT", "sku", "X"), "title of 3 to 80");
        bad(Map.of("title", "Sale"), "Choose what the banner opens");
        bad(Map.of("title", "Sale", "targetType", "PRODUCT", "sku", "NO-SUCH-SKU"), "No product NO-SUCH-SKU");
        bad(Map.of("title", "Sale", "targetType", "COUPON"), "No coupon chosen");
        bad(Map.of("title", "Sale", "targetType", "CATEGORY", "categoryId", 999999), "No category 999999");
        bad(Map.of("title", "Sale", "targetType", "PRODUCT", "sku", newSku(1), "priority", 101), "priority");
        bad(Map.of("title", "Sale", "targetType", "PRODUCT", "sku", newSku(1),
            "endsAt", LocalDateTime.now().minusDays(1).withNano(0).toString()), "already past");
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("title", "Sale", "targetType", "PRODUCT", "sku", newSku(1)))
            .post("/api/admin/promotions").then().statusCode(403);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private long category(String name, Long parentId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name));
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return given().contentType(ContentType.JSON).body(body).post("/api/admin/categories").then().statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    private String product(double price, Long categoryId) {
        String sku = newSku(100);
        var item = inventoryRepository.findById(sku).orElseThrow();
        item.setUnitPrice(BigDecimal.valueOf(price).setScale(2));
        item.setCategoryId(categoryId);
        inventoryRepository.save(item);
        return sku;
    }

    private String coupon(Map<String, Object> fields) {
        String code = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "description", "Test offer"));
        body.putAll(fields);
        given().contentType(ContentType.JSON).body(body).post("/api/admin/coupons").then().statusCode(201);
        return code;
    }

    private long promotion(Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body).post("/api/admin/promotions").then().statusCode(201)
            .extract().jsonPath().getLong("id");
    }

    private void bad(Map<String, Object> body, String reason) {
        given().contentType(ContentType.JSON).body(body).post("/api/admin/promotions").then().statusCode(400)
            .body("reason", containsString(reason));
    }

    private static Map<String, Object> line(String sku, int quantity) {
        return Map.of("sku", sku, "quantity", quantity);
    }

    private JsonPath quote(List<Map<String, Object>> items, String code) {
        return asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
            .body(Map.of("items", items, "couponCode", code)).post("/api/checkout/quote").then().statusCode(200)
            .extract().jsonPath(JsonPathConfig.jsonPathConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    }

    private JsonPath graphql(String query, Map<String, Object> variables) {
        JsonPath result = anonymous().contentType(ContentType.JSON).body(Map.of("query", query, "variables", variables))
            .post("/graphql").then().statusCode(200).extract().jsonPath();
        assertThat(result.getList("errors")).isNull();
        return result;
    }
}
