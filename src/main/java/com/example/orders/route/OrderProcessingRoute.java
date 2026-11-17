package com.example.orders.route;

import com.example.orders.model.Order;
import com.example.orders.service.AuditService;
import com.example.orders.service.CarrierService;
import com.example.orders.service.CustomerNotificationService;
import com.example.orders.service.CustomerService;
import com.example.orders.service.DeadLetterService;
import com.example.orders.service.FraudDetectionService;
import com.example.orders.service.InventoryService;
import com.example.orders.service.PaymentGatewayService;
import com.example.orders.service.WarehouseRoutingService;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.camel.model.errorhandler.DefaultErrorHandlerDefinition;
import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Phase 1 & Phase 2 Order Fulfillment Pipeline:
 *
 * Phase 1 EIPs:
 *   - Dead Letter Channel: errorHandler(deadLetterChannel(...)) quarantines the original Kafka message
 *   - Idempotent Consumer: .idempotentConsumer(header(KafkaConstants.KEY), ...)
 *
 * Phase 2 EIPs:
 *   - 2.1 Saga Coordinator EIP: .saga() coordinating distributed transactions across inventory, payment, and shipping
 *   - 2.2 Load Balancer with Failover EIP: .loadBalance().failover() switching from Stripe to Adyen on outage
 *   - 2.3 Throttler EIP: .throttle() rate limiting external payment (20/s) and SMS (10/s) APIs
 *   - 2.4 Routing Slip EIP: .routingSlip() dynamically injecting fraud evaluation for High-Value orders
 *   - 2.5 Scatter-Gather EIP: .multicast() soliciting FedEx, DHL, and UPS quotes and picking the cheapest
 *   - 2.6 Recipient List EIP: .recipientList() dynamically routing fulfillment manifests to regional warehouses
 *   - Correlation Identifier EIP: X-Correlation-ID tracked across Kafka, Camel context, and MDC logs
 *
 * Failure handling, from the inside out:
 *   1. A saga step retries its failing call three times with exponential backoff.
 *   2. The exception then reaches the saga, which runs the compensations. Saga routes must not
 *      use the Dead Letter Channel: it would swallow the exception and the saga would complete.
 *   3. The exception continues to the Kafka consumer route, which quarantines the original message.
 *
 * Compensation routes run on a new exchange without the original properties, so each
 * .saga() step passes the order id along as an option and receives it as a header.
 */
@Component
public class OrderProcessingRoute extends RouteBuilder {

    private final DeadLetterService deadLetterService;
    private final IdempotentRepository kafkaOrderIdRepository;
    private final Duration sagaTimeout;

    public OrderProcessingRoute(DeadLetterService deadLetterService,
                                @Qualifier("kafkaOrderIdRepository") IdempotentRepository kafkaOrderIdRepository,
                                @Value("${orders.saga.timeout-ms}") long sagaTimeoutMs) {
        this.deadLetterService = deadLetterService;
        this.kafkaOrderIdRepository = kafkaOrderIdRepository;
        this.sagaTimeout = Duration.ofMillis(sagaTimeoutMs);
    }

    @Override
    public void configure() {

        // =========================================================================
        // Phase 1.1: Dead Letter Channel (DLQ) with Exponential Backoff
        // =========================================================================
        errorHandler(deadLetterChannel("direct:orderDlqHandler")
            .useOriginalMessage()
            .maximumRedeliveries(0)
            .logHandled(true));

        from("direct:orderDlqHandler")
            .routeId("order-dlq-handler")
            .log(LoggingLevel.ERROR,
                 "[DLQ QUARANTINE] Message failed! Error: ${exception.message}")
            .bean(deadLetterService, "recordFailure")
            .to("kafka:order.created.DLT?brokers={{kafka.brokers}}")
            .log(LoggingLevel.INFO,
                 "[DLQ QUARANTINE] Message safely quarantined to kafka:order.created.DLT and dead_letter_events table.");

        // =========================================================================
        // Main Kafka Ingestion & Phase 2 Routing Slip
        // =========================================================================
        // One consumer per partition it is given, so a slow order holds up only its own partition
        from("kafka:order.created?brokers={{kafka.brokers}}&groupId=order-processor&autoOffsetReset=earliest"
                + "&consumersCount={{orders.kafka.order-consumers}}")
            .routeId("order-event-consumer")
            // EIP: Correlation Identifier — extract or generate correlationId and set into MDC & header
            .process(exchange -> {
                String corrId = exchange.getIn().getHeader("X-Correlation-ID", String.class);
                if (corrId == null || corrId.isBlank()) {
                    corrId = UUID.randomUUID().toString();
                    exchange.getIn().setHeader("X-Correlation-ID", corrId);
                }
                exchange.setProperty("correlationId", corrId);
                MDC.put("correlationId", corrId);
            })
            .log(LoggingLevel.INFO,
                 "[KAFKA CONSUMER] [Correlation-ID: ${header.X-Correlation-ID}] Received event from order.created (Key: ${header.CamelKafkaKey}): ${body}")

            // Phase 1.2: Idempotent Consumer Guard
            .idempotentConsumer(header(KafkaConstants.KEY), kafkaOrderIdRepository)
                .skipDuplicate(false)
                .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(true))
                    .log(LoggingLevel.WARN,
                         "[KAFKA IDEMPOTENCY GUARD] Duplicate event detected for order key: ${header.CamelKafkaKey}. Skipping downstream processing.")
                    .stop()
                .end()
            .end()

            // EIP: Message Translator — JSON payload to Order entity
            .setProperty("originalPayload", bodyAs(String.class))
            .unmarshal().json(JsonLibrary.Jackson, Order.class)
            .setProperty("currentOrder", body())
            .setProperty("currentOrderId", simple("${body.orderId}"))

            // The Kafka message can be older than the order: stop if it was cancelled meanwhile
            .filter(method(InventoryService.class, "isNotPending(${exchangeProperty.currentOrderId})"))
                .log(LoggingLevel.WARN,
                     "[PIPELINE GUARD] Order ${exchangeProperty.currentOrderId} is no longer PENDING. Skipping.")
                .stop()
            .end()

            // Phase 2.4: Routing Slip EIP
            // Every order gets account screening; High-Value orders (> $1,000) get the full fraud evaluation instead
            .choice()
                .when(simple("${body.total} > 1000"))
                    .log(LoggingLevel.INFO, "[ROUTING SLIP] High-Value order detected (${body.total} > $1,000). Injecting Fraud Check into slip...")
                    .setHeader("pipelineSlip", constant("direct:fraudEvaluation,direct:fulfillOrderSaga"))
                .otherwise()
                    .setHeader("pipelineSlip", constant("direct:accountScreening,direct:fulfillOrderSaga"))
            .end()

            // A saga step that used up its retries is marked exhausted, and the Dead Letter
            // Channel skips exhausted exchanges, so this failure is caught explicitly
            .doTry()
                .to("direct:runPipelineSlip")
            .doCatch(Exception.class)
                .setBody(exchangeProperty("originalPayload"))
                .to("direct:orderDlqHandler")
            .end();

        from("direct:runPipelineSlip")
            .routeId("run-pipeline-slip")
            .errorHandler(noErrorHandler())
            .routingSlip(header("pipelineSlip"), ",");

        // Sub-route: blacklist check for orders that skip the full evaluation
        from("direct:accountScreening")
            .routeId("account-screening-step")
            .bean(FraudDetectionService.class, "screen(${body})")
            .to("direct:applyRiskDecision");

        // Sub-route: Phase 2.4 Dynamic Fraud Evaluation
        from("direct:fraudEvaluation")
            .routeId("fraud-evaluation-step")
            .bean(FraudDetectionService.class, "evaluate(${body})")
            .to("direct:applyRiskDecision");

        // Stops the slip for a rejected order, otherwise hands the order to the next step
        from("direct:applyRiskDecision")
            .routeId("apply-risk-decision")
            .choice()
                .when(simple("${body.approved} == false"))
                    .log(LoggingLevel.WARN,
                         "[ROUTING SLIP: SECURITY HALT] Order ${body.orderId} rejected by fraud check: ${body.reason}")
                    .stop()
                .otherwise()
                    .log(LoggingLevel.INFO,
                         "[ROUTING SLIP: PASSED] Order ${body.orderId} cleared fraud evaluation. Proceeding down slip...")
                    .setBody(exchangeProperty("currentOrder"))
            .end();

        // =========================================================================
        // Phase 2.1: Distributed Saga Coordinator EIP (.saga())
        // =========================================================================
        from("direct:fulfillOrderSaga")
            .routeId("fulfill-order-saga")
            .errorHandler(noErrorHandler())
            .to("direct:runFulfillmentSaga")
            // EIP: Wire Tap — asynchronous compliance audit, outside the saga so it cannot refund a booked order.
            // Customers hear about each status from CustomerNotificationService.
            .wireTap("direct:auditOrder");

        // A saga still open after the timeout is cancelled and its compensations run. With the
        // LRA coordinator that also covers a pod that stopped mid-saga: any other pod gets the calls.
        from("direct:runFulfillmentSaga")
            .routeId("run-fulfillment-saga")
            .errorHandler(noErrorHandler())
            .saga()
                .timeout(sagaTimeout)
                .to("direct:inventorySagaStep")
                .to("direct:paymentSagaStep")
                .to("direct:logisticsSagaStep");

        // ── Saga Step 1: Inventory Reservation & Compensation ──────────────────
        from("direct:inventorySagaStep")
            .routeId("inventory-saga-step")
            .errorHandler(sagaStepRetries())
            .log(LoggingLevel.INFO,
                 "[SAGA: STEP 1 - INVENTORY] Checking inventory for Order: ${exchangeProperty.currentOrderId}...")

            // Splitter & Aggregator stock check
            .split(simple("${body.items}"), new InventoryAggregationStrategy())
                .to("direct:checkInventoryItem")
            .end()

            .choice()
                .when(simple("${body.allInStock} == true"))
                    .to("direct:reserveInventoryWithSaga")
                .otherwise()
                    .log(LoggingLevel.WARN,
                         "[SAGA: STEP 1 REJECTED] Out of stock for Order ${body.orderId}. Missing SKUs: ${body.missingSkus}")
                    .bean(InventoryService.class, "markOutOfStock(${body.orderId}, ${body.missingSkus})")
                    .stop()
            .end();

        from("direct:reserveInventoryWithSaga")
            .routeId("reserve-inventory-saga")
            .errorHandler(sagaStepRetries())
            .saga()
                .compensation("direct:compensateInventory")
                .option("orderId", exchangeProperty("currentOrderId"))
            .log(LoggingLevel.INFO,
                 "[SAGA: STEP 1 SUCCESS] Stock available. Reserving inventory...")
            .bean(InventoryService.class, "reserve(${exchangeProperty.currentOrderId}, ${header.Long-Running-Action})")
            .filter(body().isEqualTo(false))
                .log(LoggingLevel.WARN, "[SAGA: STEP 1 STOPPED] Order ${exchangeProperty.currentOrderId} was not reserved.")
                .stop()
            .end();

        // Saga Compensation Action for Inventory
        // No error handler: a failure must reach the coordinator so it retries the compensation.
        // The Long-Running-Action header names the saga being compensated; the order is only
        // touched while that saga still holds it.
        from("direct:compensateInventory")
            .routeId("compensate-inventory")
            .errorHandler(noErrorHandler())
            .log(LoggingLevel.WARN,
                 "[SAGA COMPENSATION EXECUTING] Unlocking reserved inventory for Order ${header.orderId}...")
            .bean(InventoryService.class, "compensateReservation(${header.orderId}, ${header.Long-Running-Action})");

        // Sub-route: Checks stock availability for an individual OrderItem
        from("direct:checkInventoryItem")
            .routeId("check-inventory-item")
            .bean(InventoryService.class, "checkStock(${body})");

        // ── Saga Step 2: Payment Gateway Failover with Rate Limiting (Throttler) ──
        from("direct:paymentSagaStep")
            .routeId("payment-saga-step")
            .errorHandler(sagaStepRetries())
            .saga()
                .compensation("direct:compensatePayment")
                .option("orderId", exchangeProperty("currentOrderId"))
            .filter(method(InventoryService.class, "isAwaitingPayment(${exchangeProperty.currentOrderId})").isEqualTo(false))
                .log(LoggingLevel.WARN,
                     "[SAGA: STEP 2 STOPPED] Order ${exchangeProperty.currentOrderId} no longer awaits payment. Not charging.")
                .stop()
            .end()
            // Cash on delivery: nothing to charge now; the driver collects it at the door
            .choice()
                .when(simple("${exchangeProperty.currentOrder.paymentMethod} == 'COD'"))
                    .to("direct:confirmCashOnDelivery")
                .otherwise()
                    .to("direct:chargeOnline")
            .end();

        from("direct:confirmCashOnDelivery")
            .routeId("confirm-cash-on-delivery")
            .errorHandler(noErrorHandler())
            .log(LoggingLevel.INFO,
                 "[SAGA: STEP 2 - PAYMENT] Order ${exchangeProperty.currentOrderId} is cash on delivery (${exchangeProperty.currentOrder.currency} ${exchangeProperty.currentOrder.total} at the door)")
            .bean(InventoryService.class, "confirmCashOnDelivery(${exchangeProperty.currentOrderId})")
            .filter(body().isEqualTo(false))
                .stop()
            .end();

        // A failure here reaches the payment step, whose error handler retries the call
        from("direct:chargeOnline")
            .routeId("charge-online")
            .errorHandler(noErrorHandler())
            .log(LoggingLevel.INFO,
                 "[SAGA: STEP 2 - PAYMENT] Initiating payment for Order ${exchangeProperty.currentOrderId} (${exchangeProperty.currentOrder.currency} ${exchangeProperty.currentOrder.total})...")

            // Phase 2.3: Throttler EIP — rate limits payment gateway calls to max 20 requests/second
            .throttle(20).timePeriodMillis(1000)

            // Phase 2.2: Load Balancer with Failover EIP
            // Always attempts Primary (Stripe) first; fails over to Secondary (Adyen) only on outage
            .loadBalance().failover(1, false, false)
                .to("direct:stripePaymentGateway")
                .to("direct:adyenPaymentGateway")
            .end()

            .log(LoggingLevel.INFO,
                 "[SAGA: STEP 2 SUCCESS] Payment finalized for Order ${exchangeProperty.currentOrderId}. Confirming order...")
            .bean(InventoryService.class, "confirmPayment(${exchangeProperty.currentOrderId}, ${body})")
            .filter(body().isEqualTo(false))
                .stop()
            .end();

        // Primary Gateway: Stripe
        from("direct:stripePaymentGateway")
            .routeId("stripe-payment-gateway")
            .errorHandler(noErrorHandler())
            .bean(PaymentGatewayService.class, "chargeStripe(${exchangeProperty.currentOrder})");

        // Secondary Backup Gateway: Adyen (Failover target)
        from("direct:adyenPaymentGateway")
            .routeId("adyen-payment-gateway")
            .errorHandler(noErrorHandler())
            .bean(PaymentGatewayService.class, "chargeAdyen(${exchangeProperty.currentOrder})");

        // Saga Compensation Action for Payment
        from("direct:compensatePayment")
            .routeId("compensate-payment")
            .errorHandler(noErrorHandler())
            .log(LoggingLevel.WARN,
                 "[SAGA COMPENSATION EXECUTING] Refunding any charge for Order ${header.orderId}...")
            .bean(PaymentGatewayService.class, "compensateCharge(${header.orderId}, ${header.Long-Running-Action})");

        // ── Saga Step 3: Scatter-Gather Logistics Carrier Engine ───────────────
        from("direct:logisticsSagaStep")
            .routeId("logistics-saga-step")
            .errorHandler(sagaStepRetries())
            .log(LoggingLevel.INFO,
                 "[SAGA: STEP 3 - LOGISTICS] Soliciting shipping quotes from FedEx, DHL, and UPS for Order ${exchangeProperty.currentOrderId}...")

            // Phase 2.5: Scatter-Gather EIP
            // Scatters quote requests to all 3 carriers simultaneously in parallel
            // Gathers responses using LowestShippingQuoteAggregationStrategy to pick the cheapest
            .multicast(new LowestShippingQuoteAggregationStrategy()).parallelProcessing()
                .to("direct:quoteFedEx", "direct:quoteDHL", "direct:quoteUPS")
            .end()

            .log(LoggingLevel.INFO,
                 "[SCATTER-GATHER WINNER] Lowest quote selected: ${body.carrier} ($${body.cost}, ${body.estimatedDays} days)")

            // Books with the winning carrier and updates order with tracking number
            .bean(CarrierService.class, "bookWinningCarrier(${exchangeProperty.currentOrder}, ${body})")
            .filter(body().isNull())
                .stop()
            .end()
            .setProperty("currentOrder", body())

            // Phase 2.6: Recipient List EIP
            // Dynamically determines warehouse fulfillment hubs based on shipping destination, cargo value, and partner status
            .setHeader("fulfillmentHubRecipients", method(WarehouseRoutingService.class, "resolveFulfillmentRecipients(${exchangeProperty.currentOrder})"))
            .recipientList(header("fulfillmentHubRecipients"), ",").parallelProcessing();

        // Carrier quote endpoints
        from("direct:quoteFedEx")
            .routeId("quote-fedex")
            .bean(CarrierService.class, "quoteFedEx(${exchangeProperty.currentOrder})");

        from("direct:quoteDHL")
            .routeId("quote-dhl")
            .bean(CarrierService.class, "quoteDHL(${exchangeProperty.currentOrder})");

        from("direct:quoteUPS")
            .routeId("quote-ups")
            .bean(CarrierService.class, "quoteUPS(${exchangeProperty.currentOrder})");

        // Sub-route: Wire Tap destination
        from("direct:auditOrder")
            .routeId("audit-order-wiretap")
            .bean(AuditService.class, "recordAudit(${exchangeProperty.currentOrder}, 'CARRIER_BOOKED', 'Carrier booked and tracking number generated successfully')");

        // ── Step 4: Multi-Channel Customer Notification ────────────────────────
        // Messages wait in customer_notifications, saved with the order change they describe.
        // Each pod claims a batch (FOR UPDATE SKIP LOCKED), sends it, and records the outcome;
        // a failure is retried later with a growing delay instead of being lost.
        from("timer:customerNotifications?period={{orders.notifications.poll-ms}}&delay={{orders.notifications.poll-ms}}")
            .routeId("customer-notification-outbox")
            .bean(CustomerNotificationService.class, "claimDue")
            .split(body())
                .setProperty("notificationId", simple("${body.id}"))
                .doTry()
                    .to("direct:sendNotification")
                    .bean(CustomerNotificationService.class, "markSent(${exchangeProperty.notificationId})")
                .doCatch(Exception.class)
                    .bean(CustomerNotificationService.class,
                          "markFailed(${exchangeProperty.notificationId}, ${exception.message})")
                .end()
            .end();

        // EIP: Content-Based Router - staff messages go straight to their address
        from("direct:sendNotification")
            .routeId("send-notification")
            .errorHandler(noErrorHandler())
            .choice()
                .when(simple("${body.recipientEmail} != null"))
                    .to("direct:sendStaffEmail")
                .otherwise()
                    .to("direct:sendCustomerNotification")
            .end();

        // Body: a claimed CustomerNotification. Any exception reaches the outbox route, which retries it.
        from("direct:sendCustomerNotification")
            .routeId("customer-notification")
            .errorHandler(noErrorHandler())
            .setProperty("notification", body())
            .setProperty("currentOrderId", simple("${body.orderId}"))
            .setHeader("customerId", simple("${body.customerId}"))

            // EIP: Content Enricher — attach customer profile
            .enrich("direct:lookupCustomerProfile", new CustomerAggregationStrategy())
            .filter(exchangeProperty("customerProfile").isNotNull())

            // EIP: Multicast — the same message on both channels
            .multicast().stopOnException()
                .to("direct:sendEmailNotification", "direct:sendSmsNotification")
            .end();

        // Sub-route: Content Enricher lookup
        from("direct:lookupCustomerProfile")
            .routeId("lookup-customer-profile")
            .bean(CustomerService.class, "getProfile(${header.customerId})");

        // Staff email, such as the low-stock alert: a fixed address, no customer profile or SMS
        from("direct:sendStaffEmail")
            .routeId("send-staff-email")
            .errorHandler(noErrorHandler())
            .setProperty("notification", body())
            .removeHeaders("*")
            .setHeader("To", simple("${exchangeProperty.notification.recipientEmail}"))
            .setHeader("Subject", simple("${exchangeProperty.notification.subject}"))
            .setHeader("Content-Type", constant("text/plain; charset=UTF-8"))
            .setBody(simple("${exchangeProperty.notification.text}\n"))
            .to("smtp://{{orders.mail.host}}:{{orders.mail.port}}?from={{orders.mail.from}}"
                + "&useHeaderRecipients=true&useHeaderSubject=true")
            .log(LoggingLevel.INFO,
                 "[EMAIL DISPATCH] Sent '${exchangeProperty.notification.subject}' to ${exchangeProperty.notification.recipientEmail}");

        // Email channel
        from("direct:sendEmailNotification")
            .routeId("send-email-notification")
            .errorHandler(noErrorHandler())
            // Customers imported from old orders have no email on file yet
            .filter(simple("${exchangeProperty.customerProfile.email} != null"))
            // Every remaining header would otherwise be sent as a mail header
            .removeHeaders("*")
            .setHeader("To", simple("${exchangeProperty.customerProfile.email}"))
            .setHeader("Subject", simple("${exchangeProperty.notification.subject}"))
            .setHeader("Content-Type", constant("text/plain; charset=UTF-8"))
            .setBody(simple("Dear ${exchangeProperty.customerProfile.fullName},\n\n"
                + "${exchangeProperty.notification.text}\n"))
            // Camel 4.22 ignores the To and Subject headers unless told to use them. Safe here:
            // every incoming header was removed above, and both are set from our own data
            .to("smtp://{{orders.mail.host}}:{{orders.mail.port}}?from={{orders.mail.from}}"
                + "&useHeaderRecipients=true&useHeaderSubject=true")
            .log(LoggingLevel.INFO,
                 "[EMAIL DISPATCH] Sent '${exchangeProperty.notification.subject}' to customer ${exchangeProperty.customerProfile.customerId}");

        // SMS channel with Phase 2.3 Throttler EIP
        from("direct:sendSmsNotification")
            .routeId("send-sms-notification")
            .errorHandler(noErrorHandler())
            .filter(simple("${exchangeProperty.customerProfile.phone} != null"))
            // Phase 2.3: Throttler EIP — rate limit SMS provider to max 10 requests per second
            .throttle(10).timePeriodMillis(1000)
            .log(LoggingLevel.INFO,
                 "[SMS DISPATCH] Sent SMS to customer ${exchangeProperty.customerProfile.customerId}: ${exchangeProperty.notification.subject}");

        // =========================================================================
        // Recipient List EIP Destinations (Dynamic Regional Distribution Centers)
        // =========================================================================
        from("direct:hubNorth")
            .routeId("hub-north")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: NORTH HUB] Physical manifest received at Gurugram Fulfilment Centre (HR) for Order: ${exchangeProperty.currentOrderId}");

        from("direct:hubSouth")
            .routeId("hub-south")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: SOUTH HUB] Physical manifest received at Bengaluru Fulfilment Centre (KA) for Order: ${exchangeProperty.currentOrderId}");

        from("direct:hubEast")
            .routeId("hub-east")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: EAST HUB] Physical manifest received at Kolkata Fulfilment Centre (WB) for Order: ${exchangeProperty.currentOrderId}");

        from("direct:hubWest")
            .routeId("hub-west")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: WEST HUB] Physical manifest received at Bhiwandi Fulfilment Centre (MH) for Order: ${exchangeProperty.currentOrderId}");

        from("direct:hubCentral")
            .routeId("hub-central")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: CENTRAL HUB] Physical manifest received at Nagpur Logistics Centre (MH) for Order: ${exchangeProperty.currentOrderId}");

        from("direct:hubPriorityWhiteGlove")
            .routeId("hub-priority-white-glove")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: WHITE-GLOVE VIP] High-value order manifest routed to Priority White-Glove Desk for Order: ${exchangeProperty.currentOrderId}");

        from("direct:hubPartnerDropship")
            .routeId("hub-partner-dropship")
            .log(LoggingLevel.INFO,
                 "[RECIPIENT LIST: DROPSHIP PARTNER] B2B partner dropship manifest dispatched to Partner Supplier EDI Portal for Order: ${exchangeProperty.currentOrderId}");
    }

    /**
     * Retries the failing call in a saga step, then lets the exception through to the saga.
     */
    private DefaultErrorHandlerDefinition sagaStepRetries() {
        return defaultErrorHandler()
            .maximumRedeliveries(3)
            .redeliveryDelay(1000)
            .backOffMultiplier(2.0)
            .useExponentialBackOff()
            .retryAttemptedLogLevel(LoggingLevel.WARN)
            .logRetryStackTrace(true);
    }
}
