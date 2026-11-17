package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot Main Application Class for the Order Integration Platform.
 */
@SpringBootApplication
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        // Times are stored as India wall-clock time (LocalDateTime), whatever zone the server is in
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(com.example.orders.config.TimeConfig.INDIA));
        SpringApplication.run(OrderApplication.class, args);
    }
}
