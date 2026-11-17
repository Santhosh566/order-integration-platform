package com.example.orders;

import com.example.orders.model.Role;
import com.example.orders.security.KeycloakRoles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Keycloak with ops/keycloak/order-platform-realm.json: its tokens must carry what the app reads,
 * the subject the demo users are linked by and the realm roles, and be accepted by the app's decoder.
 */
class KeycloakRealmIT {

    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.6.1")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forHostPath("ops/keycloak/order-platform-realm.json"),
                    "/opt/keycloak/data/import/order-platform-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/order-platform/.well-known/openid-configuration")
                    .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    static String issuer;
    static JwtDecoder decoder;

    @BeforeAll
    static void startKeycloak() {
        KEYCLOAK.start();
        issuer = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/order-platform";
        // The same decoder Spring Boot builds from spring.security.oauth2.resourceserver.jwt.issuer-uri
        decoder = JwtDecoders.fromIssuerLocation(issuer);
    }

    @AfterAll
    static void stopKeycloak() {
        KEYCLOAK.stop();
    }

    @Test
    void customerToken_hasTheLinkedSubject_andTheCustomerRole() throws Exception {
        Jwt jwt = decoder.decode(passwordLogin("alice.smith@example.com"));

        assertThat(jwt.getSubject()).isEqualTo(TestTokens.ALICE);
        assertThat(jwt.getClaimAsString("email")).isEqualTo("alice.smith@example.com");
        assertThat(jwt.getClaimAsBoolean("email_verified")).isTrue();
        assertThat(KeycloakRoles.of(jwt)).containsExactly(Role.CUSTOMER);
    }

    @Test
    void adminAndDriverTokens_carryTheirRoles() throws Exception {
        assertThat(KeycloakRoles.of(decoder.decode(passwordLogin("admin@example.com")))).contains(Role.ADMIN);

        Jwt driver = decoder.decode(passwordLogin("driver1@example.com"));
        assertThat(driver.getSubject()).isEqualTo(TestTokens.DRIVER_ONE);
        assertThat(KeycloakRoles.of(driver)).contains(Role.DELIVERY);
    }

    @Test
    void theWarehouseSystem_signsInWithItsClientSecret() throws Exception {
        String token = tokenRequest(Map.of("grant_type", "client_credentials",
                "client_id", "warehouse-service", "client_secret", "dev-warehouse-secret"));
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo(TestTokens.WAREHOUSE);
        assertThat(KeycloakRoles.of(jwt)).containsExactly(Role.SERVICE);
    }

    @Test
    void theWebClient_cannotBeUsedForPasswordLogin() throws Exception {
        HttpResponse<String> response = post(Map.of("grant_type", "password", "client_id", "order-platform-web",
                "username", "alice.smith@example.com", "password", "demo1234"));
        assertThat(response.statusCode()).isEqualTo(400);
    }

    private static String passwordLogin(String username) throws Exception {
        return tokenRequest(Map.of("grant_type", "password", "client_id", "order-platform-cli",
                "username", username, "password", "demo1234"));
    }

    private static String tokenRequest(Map<String, String> form) throws Exception {
        HttpResponse<String> response = post(form);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body().replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private static HttpResponse<String> post(Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(issuer + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
