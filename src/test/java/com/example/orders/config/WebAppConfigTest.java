package com.example.orders.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebAppConfigTest {

    @Test
    void screensOfTheAppAreAnsweredWithIndexHtml_filesAndServerPathsAreNot() {
        assertThat(WebAppConfig.isAppRoute("")).isTrue();
        assertThat(WebAppConfig.isAppRoute("shop")).isTrue();
        assertThat(WebAppConfig.isAppRoute("admin/orders/ORD-3f2a9c1e-1111-2222-3333-444455556666")).isTrue();
        assertThat(WebAppConfig.isAppRoute("admin/catalog/SKU-1")).isTrue();
        assertThat(WebAppConfig.isAppRoute("logo.png")).isFalse();
        assertThat(WebAppConfig.isAppRoute("assets/missing.js")).isFalse();
        assertThat(WebAppConfig.isAppRoute("api/orders")).isFalse();
        assertThat(WebAppConfig.isAppRoute("actuator/health")).isFalse();
        assertThat(WebAppConfig.isAppRoute("lra-participant/compensate")).isFalse();
    }
}
