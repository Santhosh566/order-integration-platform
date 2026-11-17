package com.example.orders.route;

import com.example.orders.service.CatalogAdminService;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Polling Consumer EIP: makes scheduled prices the catalogue price once they are due.
 */
@Component
public class CatalogRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("timer:catalogPriceScheduler?period={{orders.catalog.price-poll-ms}}&delay={{orders.catalog.price-poll-ms}}")
            .routeId("catalog-price-scheduler")
            .bean(CatalogAdminService.class, "applyDuePrices")
            .filter(simple("${body} > 0"))
                .log(LoggingLevel.INFO, "[CATALOG] Applied ${body} scheduled price(s)");
    }
}
