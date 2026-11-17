package com.example.orders;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/** Saving products, and the back-in-stock email: once, to those who asked, when stock comes back. */
class WishlistIT extends IntegrationTestBase {

    private String product(int available) {
        String sku = newSku(available);
        var item = inventoryRepository.findById(sku).orElseThrow();
        item.setProductName("Wish lamp " + UUID.randomUUID().toString().substring(0, 8));
        inventoryRepository.save(item);
        setPrice(sku, 250.0);
        return sku;
    }

    private String nameOf(String sku) {
        return inventoryRepository.findById(sku).orElseThrow().getProductName();
    }

    private void stock(String type, String sku, int quantity) {
        given().contentType(ContentType.JSON).body(Map.of("type", type, "quantity", quantity, "reason", "test"))
            .post("/api/admin/catalog/" + sku + "/stock").then().statusCode(201);
    }

    @Test
    void aCustomerSavesAndRemovesProducts() {
        String lamp = product(50);
        String retired = product(50);

        asUser(TestTokens.ALICE, "CUSTOMER").put("/api/wishlist/" + lamp).then().statusCode(200)
            .body("sku", equalTo(lamp)).body("product.stockStatus", equalTo("IN_STOCK"))
            .body("notifyWhenAvailable", equalTo(false));
        // Saving twice is harmless
        asUser(TestTokens.ALICE, "CUSTOMER").put("/api/wishlist/" + lamp).then().statusCode(200);
        asUser(TestTokens.ALICE, "CUSTOMER").put("/api/wishlist/" + retired).then().statusCode(200);

        // A product taken off sale stays on the list, without its shop details
        var item = inventoryRepository.findById(retired).orElseThrow();
        item.setActive(false);
        inventoryRepository.save(item);
        JsonPath list = asUser(TestTokens.ALICE, "CUSTOMER").get("/api/wishlist").then().statusCode(200).extract().jsonPath();
        assertThat(list.getList("sku")).contains(lamp, retired);
        assertThat(list.getString("find { it.sku == '" + retired + "' }.product")).isNull();
        assertThat(list.getString("find { it.sku == '" + retired + "' }.name")).isEqualTo(nameOf(retired));
        // ...but cannot be newly saved
        asUser(TestTokens.BOB, "CUSTOMER").put("/api/wishlist/" + retired).then().statusCode(404);

        // Each customer has their own list
        assertThat(asUser(TestTokens.BOB, "CUSTOMER").get("/api/wishlist").then().extract().jsonPath()
            .getList("sku")).doesNotContain(lamp);

        asUser(TestTokens.ALICE, "CUSTOMER").delete("/api/wishlist/" + lamp).then().statusCode(204);
        assertThat(asUser(TestTokens.ALICE, "CUSTOMER").get("/api/wishlist").then().extract().jsonPath()
            .getList("sku")).doesNotContain(lamp);

        given().get("/api/wishlist").then().statusCode(403);
        anonymous().get("/api/wishlist").then().statusCode(401);
    }

    @Test
    void whoeverAskedIsEmailedOnce_whenStockComesBack() throws Exception {
        String sku = product(0);
        String subject = "Back in stock: " + nameOf(sku);

        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("notifyWhenAvailable", true))
            .put("/api/wishlist/" + sku).then().statusCode(200).body("notifyWhenAvailable", equalTo(true));
        // Bob saved it but did not ask for an email
        asUser(TestTokens.BOB, "CUSTOMER").put("/api/wishlist/" + sku).then().statusCode(200);

        stock("RECEIPT", sku, 3);

        Awaitility.await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(recipients(subject)).containsExactly("alice.smith@example.com"));
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/wishlist").then()
            .body("find { it.sku == '" + sku + "' }.notifiedAt", notNullValue());

        // Sold out and back again: the request was answered, so no second email
        stock("WRITE_OFF", sku, 3);
        stock("RECEIPT", sku, 2);
        Thread.sleep(3000);
        assertThat(recipients(subject)).hasSize(1);

        // Asking again re-arms it
        stock("WRITE_OFF", sku, 2);
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("notifyWhenAvailable", true))
            .put("/api/wishlist/" + sku).then().statusCode(200).body("notifiedAt", nullValue());
        stock("RECEIPT", sku, 1);
        Awaitility.await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(recipients(subject)).hasSize(2));
    }

    @Test
    void puttingAProductBackOnSaleEmailsThoseWaiting() {
        String sku = product(0);
        String subject = "Back in stock: " + nameOf(sku);
        asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON).body(Map.of("notifyWhenAvailable", true))
            .put("/api/wishlist/" + sku).then().statusCode(200);

        // Taken off sale, then stocked: nothing yet, it cannot be bought
        int version = given().get("/api/admin/catalog/" + sku).then().extract().path("product.version");
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", version, "active", false))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200);
        stock("RECEIPT", sku, 4);
        asUser(TestTokens.ALICE, "CUSTOMER").get("/api/wishlist").then()
            .body("find { it.sku == '" + sku + "' }.notifiedAt", nullValue());

        // Back on sale with stock: now it can
        version = given().get("/api/admin/catalog/" + sku).then().extract().path("product.version");
        given().contentType(ContentType.JSON).body(Map.of("expectedVersion", version, "active", true))
            .patch("/api/admin/catalog/" + sku).then().statusCode(200);
        Awaitility.await().atMost(Duration.ofSeconds(30))
            .untilAsserted(() -> assertThat(recipients(subject)).containsExactly("alice.smith@example.com"));
    }

    @Test
    void aProductThatDoesNotExistCannotBeSaved() {
        asUser(TestTokens.ALICE, "CUSTOMER").put("/api/wishlist/NO-SUCH-SKU").then().statusCode(404);
    }

    /** Who received a message with this subject, one entry per message. */
    @SuppressWarnings("unchecked")
    private static List<String> recipients(String subject) throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages?limit=1000");
        String body = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
        List<Map<String, Object>> messages = JsonPath.from(body).getList("messages");
        return messages.stream().filter(m -> subject.equals(m.get("Subject")))
                .map(m -> (String) ((List<Map<String, Object>>) m.get("To")).get(0).get("Address")).toList();
    }
}
