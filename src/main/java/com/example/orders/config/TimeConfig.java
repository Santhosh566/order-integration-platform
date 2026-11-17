package com.example.orders.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** The shop runs on India time: dispatch cut-offs and delivery dates are reckoned in IST. */
@Configuration
public class TimeConfig {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Bean
    Clock clock() {
        return Clock.system(INDIA);
    }
}
