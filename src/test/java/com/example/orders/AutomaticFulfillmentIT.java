package com.example.orders;

import com.example.orders.model.OrderStatus;
import org.apache.camel.CamelContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * The full journey with the development default: nobody sends warehouse signals.
 */
@AutoConfigureMetrics
@TestPropertySource(properties = {
        "orders.fulfillment.auto-release=true",
        "orders.tracking.poll-ms=200"
})
class AutomaticFulfillmentIT extends IntegrationTestBase {

    @Autowired
    CamelContext camelContext;

    @Test
    void orderTravelsToDelivered_issuesAnInvoice_andEmailsTheCustomer() {
        String sku = newSku(5);

        String orderId = placeOrder(sku, 2, 30.0);

        awaitStatus(orderId, OrderStatus.DELIVERED);
        assertStock(sku, 3, 0);
        assertThat(order(orderId).getInvoiceNumber()).isNotNull();
        Awaitility.await("shipment emails").atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(mailpitMessages()).contains("alice.smith@example.com")
                        .contains("We received your payment for order " + orderId)
                        .contains("Your order " + orderId + " has shipped")
                        .contains("Your order " + orderId + " was delivered"));
    }

    @Test
    void customerIsTold_whenAnOrderCannotBeFulfilled() {
        String orderId = placeOrder(newSku(0), 1, 30.0);

        awaitStatus(orderId, OrderStatus.OUT_OF_STOCK);
        Awaitility.await("out of stock email").atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(mailpitMessages()).contains("We could not fulfil order " + orderId));
    }

    @Test
    void trackingResumesAfterARestart() throws Exception {
        String sku = newSku(5);
        camelContext.getRouteController().stopRoute("carrier-tracking-poller");
        String orderId = placeOrder(sku, 1, 30.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);

        camelContext.getRouteController().startRoute("carrier-tracking-poller");

        awaitStatus(orderId, OrderStatus.DELIVERED);
    }

    @Test
    void mailOutage_doesNotFailTheOrder() throws Exception {
        MAILPIT.getDockerClient().pauseContainerCmd(MAILPIT.getContainerId()).exec();
        try {
            String orderId = placeOrder(newSku(5), 1, 30.0);
            awaitStatus(orderId, OrderStatus.DELIVERED);
        } finally {
            MAILPIT.getDockerClient().unpauseContainerCmd(MAILPIT.getContainerId()).exec();
        }
    }

    @Test
    void prometheusEndpoint_publishesWhatTheGrafanaDashboardReads() {
        given().get("/api/orders").then().statusCode(200);

        given().get("/actuator/prometheus").then().statusCode(200)
                .body(containsString("http_server_requests_seconds_bucket"),
                      containsString("hikaricp_connections_active"),
                      containsString("jvm_memory_used_bytes"));
    }

    private static String mailpitMessages() throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages?limit=1000");  // all test classes share this mailbox
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }
}
