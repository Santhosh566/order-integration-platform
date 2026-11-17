package com.example.orders;

import com.example.orders.model.LiveEvent;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Live updates reach a browser whichever pod made the change: every change goes through the
 * platform.events topic, and every pod passes every event to its own browsers.
 * "Another pod" is played here by publishing to the topic directly.
 */
class LiveUpdatesIT extends IntegrationTestBase {

    @Value("${kafka.brokers}") String brokers;
    @Value("${orders.kafka.events-topic}") String topic;

    @Test
    void aChangeMadeOnAnotherPod_reachesTheRightBrowsersHere() throws Exception {
        try (Stream alice = Stream.open(TestTokens.issue(TestTokens.ALICE, "CUSTOMER"));
             Stream admin = Stream.open(TestTokens.issue(TestTokens.ADMIN, "ADMIN"))) {
            String alicesOrder = "ORD-ELSEWHERE-" + UUID.randomUUID();
            String bobsOrder = "ORD-BOB-" + UUID.randomUUID();

            // The other pod's consumer may still be joining, so keep announcing until it is heard
            await("an update from another pod").atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    publish(new LiveEvent("ORDER_UPDATED", bobsOrder, "CUST-101",
                            "{\"orderId\":\"" + bobsOrder + "\",\"fraudScore\":0.1}",
                            "{\"orderId\":\"" + bobsOrder + "\"}", "other-pod"));
                    publish(new LiveEvent("ORDER_UPDATED", alicesOrder, "CUST-100",
                            "{\"orderId\":\"" + alicesOrder + "\",\"fraudScore\":0.1}",
                            "{\"orderId\":\"" + alicesOrder + "\",\"status\":\"DELIVERED\"}", "other-pod"));
                    assertThat(alice.data("ORDER_UPDATED")).anyMatch(d -> d.contains(alicesOrder));
                    assertThat(admin.data("ORDER_UPDATED")).anyMatch(d -> d.contains(bobsOrder));
                });

            // Alice gets her own order without staff fields, and never Bob's; the admin gets both, whole
            assertThat(alice.data("ORDER_UPDATED")).filteredOn(d -> d.contains(alicesOrder))
                .allMatch(d -> !d.contains("fraudScore"));
            assertThat(alice.data("ORDER_UPDATED")).noneMatch(d -> d.contains(bobsOrder));
            assertThat(admin.data("ORDER_UPDATED")).anyMatch(d -> d.contains(alicesOrder) && d.contains("fraudScore"));

            // Staff-only events never reach a customer
            publish(new LiveEvent("DLQ_UPDATED", "DLQ_UPDATED", null, "{\"id\":424242}", null, "other-pod"));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(admin.data("DLQ_UPDATED")).anyMatch(d -> d.contains("424242")));
            assertThat(alice.data("DLQ_UPDATED")).isEmpty();
        }
    }

    @Test
    void aRealOrderChange_travelsThroughKafka_toTheCustomersStream() throws Exception {
        try (Stream alice = Stream.open(TestTokens.issue(TestTokens.ALICE, "CUSTOMER"))) {
            // Wait until this pod's live consumer is reading, so the first change is not missed
            String probe = "ORD-PROBE-" + UUID.randomUUID();
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
                publish(new LiveEvent("ORDER_UPDATED", probe, "CUST-100", "{}", "{\"orderId\":\"" + probe + "\"}", "test"));
                assertThat(alice.data("ORDER_UPDATED")).anyMatch(d -> d.contains(probe));
            });

            String sku = newSku(10);
            setPrice(sku, 25.0);
            String orderId = asUser(TestTokens.ALICE, "CUSTOMER").contentType(ContentType.JSON)
                .body(Map.of("items", List.of(Map.of("sku", sku, "quantity", 1))))
                .post("/api/orders").then().statusCode(202).extract().path("orderId");

            await("the order's updates on Alice's stream").atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(alice.data("ORDER_UPDATED")).anyMatch(d -> d.contains(orderId) && d.contains("\"SHIPPED\"")));
            assertThat(alice.data("ORDER_UPDATED")).filteredOn(d -> d.contains(orderId))
                .allMatch(d -> !d.contains("fraudScore") && !d.contains("shippingCost"));

            // It went through the topic, keyed by order so its events stay in order
            assertThat(keysOnTopic()).contains(orderId);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void publish(LiveEvent event) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(topic, event.key(), JsonMapper.shared().writeValueAsString(event))).get();
        }
    }

    private List<String> keysOnTopic() {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    keys.add(record.key());
                }
            }
            return keys;
        }
    }

    /** A browser's EventSource: the raw text/event-stream, read on a thread of its own. */
    static final class Stream implements AutoCloseable {
        private final List<String[]> events = new CopyOnWriteArrayList<>();
        private final InputStream body;
        private final Thread reader;

        private Stream(InputStream body) {
            this.body = body;
            this.reader = Thread.ofVirtual().start(this::read);
        }

        static Stream open(String token) throws Exception {
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port
                        + "/api/stream/orders?access_token=" + token)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            Stream stream = new Stream(response.body());
            await().atMost(Duration.ofSeconds(10)).until(() -> !stream.data("INIT").isEmpty());
            return stream;
        }

        private void read() {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String name = null;
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        events.add(new String[] {name, line.substring(5)});
                    }
                }
            } catch (Exception closed) {
                // The test closed the stream
            }
        }

        List<String> data(String name) {
            return events.stream().filter(e -> name.equals(e[0])).map(e -> e[1]).toList();
        }

        @Override
        public void close() throws Exception {
            body.close();
            reader.interrupt();
        }
    }
}
