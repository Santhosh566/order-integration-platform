package com.example.orders.route;

import com.example.orders.service.OrderService;
import com.example.orders.service.StuckOrderRecoveryService;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Polling Consumer EIP: finds orders that stopped part way through the pipeline and
 * finishes or unwinds them. See StuckOrderRecoveryService for what happens to each status.
 */
@Component
public class RecoveryRoute extends RouteBuilder {

    private final StuckOrderRecoveryService recoveryService;

    public RecoveryRoute(StuckOrderRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    public void configure() {

        from("timer:stuckOrderRecovery?period={{orders.recovery.poll-ms}}&delay={{orders.recovery.poll-ms}}")
            .routeId("stuck-order-recovery")
            .bean(recoveryService, "recover")
            .split(body())
                .to("direct:resumePaidOrder")
            .end();

        // A paid order is shipped rather than refunded; only when that fails is the money returned
        from("direct:resumePaidOrder")
            .routeId("resume-paid-order")
            .errorHandler(noErrorHandler())
            .setHeader("id", body())
            .bean(OrderService.class, "findById")
            .setProperty("currentOrder", body())
            .setProperty("currentOrderId", simple("${body.orderId}"))
            .log(LoggingLevel.WARN, "[RECOVERY] Resuming shipping for paid order ${exchangeProperty.currentOrderId}")
            .doTry()
                .to("direct:logisticsSagaStep")
            .doCatch(Exception.class)
                .log(LoggingLevel.ERROR,
                     "[RECOVERY] Could not ship order ${exchangeProperty.currentOrderId}: ${exception.message}")
                .bean(recoveryService, "abandonPaidOrder(${exchangeProperty.currentOrderId})")
            .end()
            // Audited only when the carrier was booked
            .filter(exchangeProperty(Exchange.EXCEPTION_CAUGHT).isNull())
                .wireTap("direct:auditOrder");
    }
}
