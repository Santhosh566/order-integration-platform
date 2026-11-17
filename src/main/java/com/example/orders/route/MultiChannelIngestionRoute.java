package com.example.orders.route;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Order;
import com.example.orders.model.csv.CsvOrderItemRecord;
import com.example.orders.model.xml.XmlPurchaseOrder;
import com.example.orders.service.OrderNormalizer;
import com.example.orders.service.OrderValidator;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.bindy.csv.BindyCsvDataFormat;
import org.apache.camel.model.dataformat.JaxbDataFormat;
import org.apache.camel.ValidationException;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.support.processor.validation.SchemaValidationException;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Phase 3: Multi-Channel Ingestion & Normalizer EIP
 *
 * Ingestion Channels:
 *   1. B2B Dropship File Watcher: data/inbox/csv/ (CSV unmarshalled via Bindy)
 *   2. Supplier XML Ingestion: data/inbox/xml/ & POST /api/orders/xml (JAXB unmarshalled)
 *   3. Strict JSON Schema Validation: POST /api/orders/validated (json-validator)
 *
 * EIP Patterns used:
 *   - Normalizer EIP: OrderNormalizer maps CSV records and XML trees to Canonical Order
 *   - Message Translator EIP: Bindy CSV and JAXB XML
 *   - Idempotent File Watcher: Prevents re-processing uploaded dropship files
 *   - Content-Based Schema Validation: Formal schema validation contracts
 */
@Component
public class MultiChannelIngestionRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {

        BindyCsvDataFormat csvFormat = new BindyCsvDataFormat(CsvOrderItemRecord.class);

        // Schema validation exception handler for /api/orders/validated (JSON Schema)
        onException(JsonValidationException.class)
            .handled(true)
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .process(exchange -> {
                JsonValidationException ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, JsonValidationException.class);
                String details = "Schema validation failed";
                if (ex != null && ex.getErrors() != null && !ex.getErrors().isEmpty()) {
                    details = ex.getErrors().stream()
                        .map(com.networknt.schema.Error::getMessage)
                        .collect(Collectors.joining("; "));
                } else if (ex != null && ex.getMessage() != null) {
                    details = ex.getMessage();
                }
                // Strip Exchange[] artifacts and sanitize quotes/newlines for valid JSON
                if (details.contains("Exchange[")) {
                    details = details.substring(0, details.indexOf("Exchange[")).trim();
                }
                String cleanDetails = details.replace("\"", "'").replace("\n", " ").replace("\r", "");
                exchange.getMessage().setBody(String.format("{\"status\":\"SCHEMA_VALIDATION_ERROR\",\"reason\":\"%s\"}", cleanDetails));
            })
            .log(LoggingLevel.WARN, "[JSON SCHEMA REJECTED] ${body}");

        onException(SchemaValidationException.class, ValidationException.class)
            .handled(true)
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .process(exchange -> {
                Exception ex = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
                String msg = ex != null && ex.getMessage() != null ? ex.getMessage() : "Validation error";
                if (msg.contains("Exchange[")) {
                    msg = msg.substring(0, msg.indexOf("Exchange[")).trim();
                }
                String cleanMsg = msg.replace("\"", "'").replace("\n", " ").replace("\r", "");
                exchange.getMessage().setBody(String.format("{\"status\":\"VALIDATION_ERROR\",\"reason\":\"%s\"}", cleanMsg));
            })
            .log(LoggingLevel.WARN, "[VALIDATION REJECTED] ${body}");

        onException(InvalidOrderException.class)
            .handled(true)
            .log(LoggingLevel.WARN, "Order rejected: ${exception.message}")
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(400))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(simple("{\"status\":\"REJECTED\",\"reason\":\"${exception.message}\"}"));

        // =========================================================================
        // Channel 1: B2B Partner Dropship CSV File Watcher
        // =========================================================================
        from("file:{{orders.inbox-dir}}/csv?move=../archive/${file:name}&moveFailed=../error/${file:name}&readLock=changed&idempotent=true")
            .routeId("b2b-csv-file-watcher")
            // Unhandled here, so a bad file goes to the error folder and not the archive
            .onException(InvalidOrderException.class).handled(false).end()
            .log(LoggingLevel.INFO, "[CSV INGESTION] Detected new dropship file: ${header.CamelFileName}")

            // EIP: Message Translator — parse CSV lines into CsvOrderItemRecord list
            .unmarshal(csvFormat)

            // EIP: Normalizer — translate CSV rows into Canonical Order list
            .bean(OrderNormalizer.class, "normalizeFromCsv")

            // The whole file is checked first, so a bad line cannot leave it half imported
            .bean(OrderValidator.class, "validateAll")

            // Split across normalized orders so each order is processed independently
            .split(body())
                .log(LoggingLevel.INFO,
                     "[CSV NORMALIZED] Handing off normalized Order ${body.orderId} (Total: $${body.total}) to fulfillment pipeline...")
                .to("direct:createNormalizedOrder")
            .end()

            .log(LoggingLevel.INFO, "[CSV INGESTION COMPLETED] Finished processing file: ${header.CamelFileName}. Moved to data/inbox/archive/");

        // REST CSV endpoint for direct HTTP upload
        rest("/api/orders/csv")
            .post()
                .consumes("text/csv,text/plain")
                .produces("application/json")
                .to("direct:ingestCsvRest");

        from("direct:ingestCsvRest")
            .routeId("ingest-csv-rest")
            .log(LoggingLevel.INFO, "[CSV REST INGESTION] Received CSV dropship order(s) via HTTP POST")
            .unmarshal(csvFormat)
            .bean(OrderNormalizer.class, "normalizeFromCsv")
            .bean(OrderValidator.class, "validateAll")
            .split(body())
                .log(LoggingLevel.INFO,
                     "[CSV REST NORMALIZED] Handing off normalized Order ${body.orderId} (Total: $${body.total}) to fulfillment pipeline...")
                .to("direct:createNormalizedOrder")
            .end()
            .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setBody(constant("{\"status\":\"ACCEPTED\",\"message\":\"CSV orders ingested and processing\"}"));

        // =========================================================================
        // Channel 2: Supplier XML Ingestion (File Watcher + REST Endpoint)
        // =========================================================================
        from("file:{{orders.inbox-dir}}/xml?move=../archive/${file:name}&moveFailed=../error/${file:name}&readLock=changed&idempotent=true")
            .routeId("supplier-xml-file-watcher")
            .onException(InvalidOrderException.class).handled(false).end()
            .log(LoggingLevel.INFO, "[XML INGESTION] Detected supplier XML purchase order: ${header.CamelFileName}")

            // EIP: Message Translator — JAXB unmarshal XML to XmlPurchaseOrder
            .unmarshal().jaxb("com.example.orders.model.xml")

            // EIP: Normalizer — translate XML tree into Canonical Order
            .bean(OrderNormalizer.class, "normalizeFromXml")
            .bean(OrderValidator.class, "validate")
            .to("direct:createNormalizedOrder")
            .log(LoggingLevel.INFO, "[XML INGESTION COMPLETED] Finished processing XML file: ${header.CamelFileName}. Moved to archive/");

        // REST XML endpoint
        rest("/api/orders/xml")
            .post()
                .consumes("application/xml")
                .produces("application/json")
                .to("direct:ingestXmlRest");

        from("direct:ingestXmlRest")
            .routeId("ingest-xml-rest")
            .log(LoggingLevel.INFO, "[XML REST INGESTION] Received XML purchase order via HTTP POST")
            .unmarshal().jaxb("com.example.orders.model.xml")
            .bean(OrderNormalizer.class, "normalizeFromXml")
            .bean(OrderValidator.class, "validate")
            .to("direct:createNormalizedOrder");

        // =========================================================================
        // Channel 3: REST JSON with Formal JSON Schema Validator
        // =========================================================================
        rest("/api/orders/validated")
            .post()
                .consumes("application/json")
                .produces("application/json")
                .to("direct:ingestValidatedJsonRest");

        from("direct:ingestValidatedJsonRest")
            .routeId("ingest-validated-json-rest")
            .convertBodyTo(String.class)

            // EIP: Schema Validation against order-schema.json
            .to("json-validator:schema/order-schema.json")
            .log(LoggingLevel.INFO, "[JSON SCHEMA PASSED] Request body verified against order-schema.json")

            // Re-route to existing validated createOrder flow
            .to("direct:createOrder");

        // =========================================================================
        // Common Normalized Order Hand-off Destination
        // =========================================================================
        from("direct:createNormalizedOrder")
            .routeId("create-normalized-order")
            .setHeader("orderId", simple("${body.orderId}"))
            .setHeader("customerId", simple("${body.customerId}"))
            // Hand off directly to Phase 1 & 2 Transactional Client pipeline!
            .to("direct:processOrder");
    }
}
