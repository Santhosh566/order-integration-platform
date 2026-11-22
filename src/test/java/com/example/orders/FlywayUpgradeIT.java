package com.example.orders;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An existing database built by ddl-auto has tables but no Flyway history.
 * It must be adopted at V1 and receive only the newer migrations, keeping its rows.
 */
class FlywayUpgradeIT {

    @Test
    void existingDatabase_isBaselinedAndKeepsItsData() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestImages.POSTGRES)) {
            postgres.start();
            String url = postgres.getJdbcUrl();

            // Build the old schema, then remove the history so it looks like a pre-Flyway database
            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .target("1").load().migrate();
            try (Connection connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement sql = connection.createStatement()) {
                sql.execute("DROP TABLE flyway_schema_history");
                sql.execute("INSERT INTO orders (order_id, customer_id, status, total) VALUES ('ORD-OLD', 'CUST-1', 'DELIVERED', 45)");
            }

            Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .baselineOnMigrate(true).baselineVersion("1").load().migrate();

            try (Connection connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement sql = connection.createStatement();
                 ResultSet rows = sql.executeQuery(
                         "SELECT status, invoice_number, currency, total, subtotal, tax_total FROM orders WHERE order_id = 'ORD-OLD'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("DELIVERED");
                assertThat(rows.getString("invoice_number")).isNull();
                // Priced in dollars without tax before GST; the amount is kept exactly
                assertThat(rows.getString("currency")).isEqualTo("USD");
                assertThat(rows.getBigDecimal("total")).isEqualByComparingTo("45.00");
                assertThat(rows.getBigDecimal("subtotal")).isEqualByComparingTo("45.00");
                assertThat(rows.getBigDecimal("tax_total")).isEqualByComparingTo("0");
            }

            // The customer on the old order became a user who has no contact details yet
            try (Connection connection = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                 Statement sql = connection.createStatement();
                 ResultSet rows = sql.executeQuery(
                         "SELECT u.full_name, u.email, r.role FROM app_users u JOIN user_roles r USING (user_id) WHERE u.user_id = 'CUST-1'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("full_name")).isEqualTo("CUST 1");
                assertThat(rows.getString("email")).isNull();
                assertThat(rows.getString("role")).isEqualTo("CUSTOMER");
            }
        }
    }
}
