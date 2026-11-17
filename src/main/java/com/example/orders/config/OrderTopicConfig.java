package com.example.orders.config;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Gives order.created enough partitions for its consumers, before the routes start, and creates
 * platform.events, the short-lived topic that carries live updates between pods.
 *
 * Orders are spread over the partitions by order id, so the orders of one partition run one at
 * a time while the other partitions carry on. Partitions are the upper limit on consumers across
 * all pods together: with 6 partitions, a seventh consumer would sit idle. A topic can gain
 * partitions but never lose them.
 */
@Configuration
public class OrderTopicConfig implements InitializingBean {

    static final String ORDER_TOPIC = "order.created";

    private static final Logger log = LoggerFactory.getLogger(OrderTopicConfig.class);

    /** Live updates are only of use for a moment: a reconnecting page reloads instead of replaying. */
    static final String EVENTS_RETENTION_MS = String.valueOf(60 * 60 * 1000);
    static final int EVENTS_PARTITIONS = 3;

    private final String brokers;
    private final int partitions;
    private final short replicationFactor;
    private final String eventsTopic;

    public OrderTopicConfig(@Value("${kafka.brokers}") String brokers,
                            @Value("${orders.kafka.order-partitions}") int partitions,
                            @Value("${orders.kafka.replication-factor}") short replicationFactor,
                            @Value("${orders.kafka.events-topic}") String eventsTopic) {
        this.brokers = brokers;
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.eventsTopic = eventsTopic;
    }

    @Override
    public void afterPropertiesSet() throws InterruptedException {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15000))) {
            java.util.Set<String> existing = admin.listTopics().names().get(15, TimeUnit.SECONDS);
            if (!existing.contains(eventsTopic)) {
                create(admin, new NewTopic(eventsTopic, EVENTS_PARTITIONS, replicationFactor)
                        .configs(Map.of("retention.ms", EVENTS_RETENTION_MS)));
            }
            if (!existing.contains(ORDER_TOPIC)) {
                create(admin, new NewTopic(ORDER_TOPIC, partitions, replicationFactor));
                return;
            }
            int current = admin.describeTopics(List.of(ORDER_TOPIC)).allTopicNames().get(15, TimeUnit.SECONDS)
                    .get(ORDER_TOPIC).partitions().size();
            if (current < partitions) {
                log.info("[KAFKA] Raising {} from {} to {} partitions", ORDER_TOPIC, current, partitions);
                admin.createPartitions(Map.of(ORDER_TOPIC, NewPartitions.increaseTo(partitions))).all().get(15, TimeUnit.SECONDS);
            }
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            // The consumers still work on whatever partitions there are, just with less parallelism
            log.warn("[KAFKA] Could not check the topics {} and {}: {}", ORDER_TOPIC, eventsTopic, e.getMessage());
        }
    }

    private void create(Admin admin, NewTopic topic) throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        try {
            admin.createTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS);
            log.info("[KAFKA] Created {} with {} partitions", topic.name(), topic.numPartitions());
        } catch (ExecutionException e) {
            // Another pod created it first
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
    }
}
