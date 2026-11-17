package com.example.orders;

import com.example.orders.model.OrderStatus;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Reviews: only from buyers; ratings alone count at once, words wait for an admin; the average follows. */
class ReviewIT extends IntegrationTestBase {

    private static final String PRODUCT = """
            query($sku: ID!, $sort: ReviewSort) { product(sku: $sku) {
              rating { average count distribution reviewCount }
              reviews(sort: $sort) { totalItems items { author rating title body verifiedPurchase edited } } } }""";

    private String product() {
        String sku = newSku(50);
        var item = inventoryRepository.findById(sku).orElseThrow();
        item.setProductName("Review kettle " + UUID.randomUUID().toString().substring(0, 8));
        inventoryRepository.save(item);
        setPrice(sku, 899.0);
        return sku;
    }

    private RequestSpecification alice() {
        return asUser(TestTokens.ALICE, "CUSTOMER");
    }

    private void delivered(String sku) {
        String orderId = alice().contentType(ContentType.JSON)
            .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
            .post("/api/orders").then().statusCode(202).extract().path("orderId");
        awaitStatus(orderId, OrderStatus.DELIVERED);
    }

    private static Map<String, Object> review(int rating, String title, String body) {
        Map<String, Object> m = new HashMap<>();
        m.put("rating", rating);
        m.put("title", title);
        m.put("body", body);
        return m;
    }

    private JsonPath shop(String sku, String sort) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("sku", sku);
        variables.put("sort", sort);
        return anonymous().contentType(ContentType.JSON).body(Map.of("query", PRODUCT, "variables", variables))
            .post("/graphql").then().statusCode(200).extract().jsonPath();
    }

    @Test
    void onlyABuyerCanReview_andARatingAloneCountsAtOnce() {
        String sku = product();

        // Not delivered to her yet
        alice().get("/api/reviews/" + sku).then().statusCode(200).body("canReview", equalTo(false));
        alice().contentType(ContentType.JSON).body(review(5, null, null)).put("/api/reviews/" + sku).then().statusCode(409);

        delivered(sku);
        assertThat(alice().get("/api/reviews").then().statusCode(200).extract().jsonPath().getList("toReview.sku")).contains(sku);
        alice().get("/api/reviews/" + sku).then().body("canReview", equalTo(true)).body("review", nullValue());

        alice().contentType(ContentType.JSON).body(review(0, null, null)).put("/api/reviews/" + sku).then().statusCode(400);
        alice().contentType(ContentType.JSON).body(review(4, " ", null)).put("/api/reviews/" + sku).then().statusCode(200)
            .body("status", equalTo("APPROVED")).body("author", equalTo("Alice S."));

        JsonPath shop = shop(sku, null);
        assertThat(shop.getDouble("data.product.rating.average")).isEqualTo(4.0);
        assertThat(shop.getInt("data.product.rating.count")).isEqualTo(1);
        assertThat(shop.getList("data.product.rating.distribution")).containsExactly(0, 0, 0, 1, 0);
        // Nothing to read: ratings alone are not listed
        assertThat(shop.getInt("data.product.reviews.totalItems")).isZero();
        assertThat(alice().get("/api/reviews").then().extract().jsonPath().getList("toReview.sku")).doesNotContain(sku);

        // Bob never bought it
        asUser(TestTokens.BOB, "CUSTOMER").contentType(ContentType.JSON).body(review(1, null, null))
            .put("/api/reviews/" + sku).then().statusCode(409);
        anonymous().get("/api/reviews").then().statusCode(401);
        given().get("/api/reviews").then().statusCode(403);
        alice().get("/api/admin/reviews").then().statusCode(403);

        // Deleting it takes it out of the average
        alice().delete("/api/reviews/" + sku).then().statusCode(204);
        assertThat(shop(sku, null).getInt("data.product.rating.count")).isZero();
    }

    @Test
    void wordsWaitForAnAdmin_andEditingSendsThemBack() {
        String sku = product();
        delivered(sku);

        long id = alice().contentType(ContentType.JSON).body(review(2, "Leaks", "The lid does not close.  "))
            .put("/api/reviews/" + sku).then().statusCode(200)
            .body("status", equalTo("PENDING")).body("body", equalTo("The lid does not close."))
            .extract().jsonPath().getLong("id");
        assertThat(shop(sku, null).getInt("data.product.rating.count")).isZero();
        assertThat(given().get("/api/admin/reviews").then().statusCode(200).extract().jsonPath().getList("id", Long.class)).contains(id);

        given().post("/api/admin/reviews/" + id + "/approve").then().statusCode(200).body("status", equalTo("APPROVED"));
        // Decided once
        given().post("/api/admin/reviews/" + id + "/approve").then().statusCode(409);

        JsonPath shop = shop(sku, "LOWEST");
        assertThat(shop.getDouble("data.product.rating.average")).isEqualTo(2.0);
        assertThat(shop.getInt("data.product.rating.reviewCount")).isEqualTo(1);
        assertThat(shop.getString("data.product.reviews.items[0].title")).isEqualTo("Leaks");
        assertThat(shop.getBoolean("data.product.reviews.items[0].verifiedPurchase")).isTrue();
        assertThat(shop.getBoolean("data.product.reviews.items[0].edited")).isFalse();

        // An edit with words goes back to the queue and leaves the average meanwhile
        alice().contentType(ContentType.JSON).body(review(3, "Leaks a little", null)).put("/api/reviews/" + sku)
            .then().statusCode(200).body("status", equalTo("PENDING")).body("id", equalTo((int) id));
        assertThat(shop(sku, null).getInt("data.product.rating.count")).isZero();
        given().post("/api/admin/reviews/" + id + "/approve").then().statusCode(200);
        shop = shop(sku, null);
        assertThat(shop.getDouble("data.product.rating.average")).isEqualTo(3.0);
        assertThat(shop.getBoolean("data.product.reviews.items[0].edited")).isTrue();
    }

    @Test
    void aRejectedReviewIsNotShown_andTheCustomerIsToldWhy() {
        String sku = product();
        delivered(sku);
        String name = inventoryRepository.findById(sku).orElseThrow().getProductName();

        long id = alice().contentType(ContentType.JSON).body(review(1, "Call me", "Ring 98450 00000 for a cheaper one"))
            .put("/api/reviews/" + sku).then().statusCode(200).extract().jsonPath().getLong("id");

        given().contentType(ContentType.JSON).body(Map.of()).post("/api/admin/reviews/" + id + "/reject").then().statusCode(400);
        given().contentType(ContentType.JSON).body(Map.of("note", "Reviews may not contain phone numbers."))
            .post("/api/admin/reviews/" + id + "/reject").then().statusCode(200)
            .body("status", equalTo("REJECTED")).body("decisionNote", equalTo("Reviews may not contain phone numbers."));

        assertThat(shop(sku, null).getInt("data.product.rating.count")).isZero();
        alice().get("/api/reviews/" + sku).then().body("review.status", equalTo("REJECTED"));
        assertThat(given().get("/api/admin/reviews?status=REJECTED").then().extract().jsonPath().getList("id", Long.class)).contains(id);

        String subject = "Your review of " + name + " was not published";
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(mailText(subject)).contains("Reviews may not contain phone numbers."));
    }

    /** The text of the newest message with this subject, or "" when there is none. */
    private static String mailText(String subject) throws Exception {
        String base = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/";
        HttpClient http = HttpClient.newHttpClient();
        String list = http.send(HttpRequest.newBuilder(URI.create(base + "messages?limit=1000")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        List<Map<String, Object>> messages = JsonPath.from(list).getList("messages");
        String id = messages.stream().filter(m -> subject.equals(m.get("Subject"))).map(m -> (String) m.get("ID"))
                .findFirst().orElse(null);
        if (id == null) {
            return "";
        }
        String message = http.send(HttpRequest.newBuilder(URI.create(base + "message/" + id)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return JsonPath.from(message).getString("Text");
    }
}
