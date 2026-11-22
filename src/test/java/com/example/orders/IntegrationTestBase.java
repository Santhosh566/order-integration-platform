package com.example.orders;

import com.example.orders.model.InventoryItem;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.OrderRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real application against throwaway PostgreSQL, Kafka and Mailpit containers.
 * The containers are shared by all test classes and removed when the JVM exits.
 *
 * Each class's application is closed when the class ends. Every running application joins the
 * same Kafka consumer group and runs the same pollers, so a cached one from another class would
 * take messages and orders meant for this one, without this class's mocks and settings.
 *
 * Requests carry tokens from TestTokens, which the application accepts in place of Keycloak's.
 * given() is the admin by default; asUser() is anyone else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@Import(IntegrationTestBase.TestJwtDecoder.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestTokens.ISSUER,
        // Most tests follow an order to DELIVERED; DeliveryIT switches this off
        "orders.delivery.simulate=true",
        // Tests make their own products; DemoCatalogueIT turns the demo shop on
        "orders.demo.catalogue=false",
        // Tests import their own small PIN directory (ShopPageFeaturesIT), never a local download
        "orders.pin-directory.file=",
        // Sagas in memory; LraSagaIT runs them through a real LRA coordinator
        "camel.lra.enabled=false",
        "orders.mail.host=${test.mailpit.host}",
        "orders.mail.port=${test.mailpit.port}"
})
abstract class IntegrationTestBase {

    /** Trusts the test signing key instead of fetching Keycloak's. */
    @TestConfiguration
    static class TestJwtDecoder {
        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            try (InputStream in = IntegrationTestBase.class.getResourceAsStream("/jwt/test-public.pem")) {
                String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII)
                        .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
                decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TestTokens.ISSUER));
                return decoder;
            }
        }
    }

    /** PostgreSQL 15 with PostGIS, as in compose.yaml */
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>("axllent/mailpit:v1.21").withExposedPorts(1025, 8025);

    static {
        POSTGRES.start();
        KAFKA.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("kafka.brokers", KAFKA::getBootstrapServers);
        registry.add("camel.component.kafka.brokers", KAFKA::getBootstrapServers);
        // Read by orders.mail.* above, which a test class can still override
        registry.add("test.mailpit.host", MAILPIT::getHost);
        registry.add("test.mailpit.port", () -> MAILPIT.getMappedPort(1025));
    }

    @LocalServerPort
    int port;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @BeforeEach
    void pointRestAssuredAtTheApp() {
        RestAssured.port = port;
    }

    /** A request as the demo admin. Shadows RestAssured.given() in every test class. */
    RequestSpecification given() {
        return asUser(TestTokens.ADMIN, "ADMIN");
    }

    RequestSpecification asUser(String keycloakSubject, String... roles) {
        return RestAssured.given().header("Authorization", "Bearer " + TestTokens.issue(keycloakSubject, roles));
    }

    RequestSpecification anonymous() {
        return RestAssured.given();
    }

    /** Creates a stock row of its own for one test, priced at ₹10 with 18% GST, so tests cannot affect each other. */
    String newSku(int available) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        inventoryRepository.save(new InventoryItem(sku, "Test product", new BigDecimal("10.00"), available));
        return sku;
    }

    /** Orders are charged the catalogue price, so a test that needs another price sets it here. */
    void setPrice(String sku, double price) {
        InventoryItem item = inventoryRepository.findById(sku).orElseThrow();
        item.setUnitPrice(BigDecimal.valueOf(price).setScale(2));
        inventoryRepository.save(item);
    }

    ValidatableResponse submitOrder(Map<String, Object> order) {
        return given().contentType(ContentType.JSON).body(order).post("/api/orders").then();
    }

    String placeOrder(String sku, int quantity, double price) {
        setPrice(sku, price);
        return submitOrder(Map.of(
                "customerId", "CUST-100",
                "items", java.util.List.of(Map.of("sku", sku, "quantity", quantity, "price", price))))
            .statusCode(202)
            .extract().path("orderId");
    }

    ValidatableResponse cancel(String orderId) {
        return given().post("/api/orders/" + orderId + "/cancel").then();
    }

    Order order(String orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    void awaitStatus(String orderId, OrderStatus expected) {
        Awaitility.await("order " + orderId + " to become " + expected)
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(order(orderId).getStatus()).isEqualTo(expected));
    }

    void assertStock(String sku, int available, int reserved) {
        InventoryItem item = inventoryRepository.findById(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("available").isEqualTo(available);
        assertThat(item.getReservedQuantity()).as("reserved").isEqualTo(reserved);
    }

    /** The invoice is a PDF; its text is read back to check what it says. */
    String invoiceText(String orderId) throws java.io.IOException {
        byte[] pdf = given().get("/api/invoices/" + orderId).then()
                .statusCode(200).contentType("application/pdf").extract().asByteArray();
        com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
        try {
            com.lowagie.text.pdf.parser.PdfTextExtractor extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }
}
