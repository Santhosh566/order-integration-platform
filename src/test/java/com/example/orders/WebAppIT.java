package com.example.orders;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The backend serves the web app on its own origin: every screen of the app loads index.html, built
 * files are cached for good, and nothing of the API is ever answered by the app.
 * The real build is copied in by the "web" Maven profile; here a stand-in sits in src/test/resources/static.
 */
class WebAppIT extends IntegrationTestBase {

    @Test
    void theAppAndItsScreens_loadWithoutSigningIn() {
        anonymous().get("/").then().statusCode(200)
            .contentType(containsString("text/html"))
            .header("Cache-Control", containsString("no-cache"))
            .body(containsString("<div id=\"root\">"));
        // A reload on a screen of the app, or Keycloak sending the user back after sign-in
        for (String screen : new String[] {"/shop", "/admin/orders/ORD-1234", "/delivery/mine", "/auth/callback"}) {
            anonymous().get(screen).then().statusCode(200).body(containsString("<div id=\"root\">"));
        }
    }

    @Test
    void builtFiles_areCachedForAYear() {
        anonymous().get("/assets/index-abc123.js").then().statusCode(200)
            .header("Cache-Control", containsString("max-age=31536000"))
            .header("Cache-Control", containsString("immutable"))
            .body(containsString("console.log"));
    }

    @Test
    void theApiIsNeverAnsweredByTheApp() {
        anonymous().get("/api/orders").then().statusCode(401);
        given().get("/api/no-such-endpoint").then().statusCode(404).body(not(containsString("<div id=\"root\">")));
        anonymous().get("/missing-logo.png").then().statusCode(404);
        anonymous().post("/shop").then().statusCode(401);
    }
}
