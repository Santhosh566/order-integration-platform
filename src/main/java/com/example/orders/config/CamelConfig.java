package com.example.orders.config;

import org.apache.camel.processor.aggregate.jdbc.JdbcAggregationRepository;
import org.apache.camel.processor.idempotent.jdbc.JdbcMessageIdRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Camel beans shared by the routes.
 */
@Configuration
public class CamelConfig {

    /**
     * Remembers processed order ids in the CAMEL_MESSAGEPROCESSED table,
     * so duplicates are still caught after a restart.
     */
    @Bean
    public JdbcMessageIdRepository orderIdRepository(DataSource dataSource) {
        JdbcMessageIdRepository repository = new JdbcMessageIdRepository(dataSource, "order-api");
        // The table comes from the Flyway migrations
        repository.setCreateTableIfNotExists(false);
        return repository;
    }

    /**
     * Remembers processed order ids for the Kafka consumer route.
     * Uses processorName 'kafka-order-consumer' so it has its own independent deduplication scope.
     */
    @Bean
    public JdbcMessageIdRepository kafkaOrderIdRepository(DataSource dataSource) {
        JdbcMessageIdRepository repository = new JdbcMessageIdRepository(dataSource, "kafka-order-consumer");
        repository.setCreateTableIfNotExists(false);
        return repository;
    }

    /**
     * Keeps partly collected fulfillment signals in PostgreSQL, so a waiting order
     * survives a restart and pods share the same state.
     * Not PostgresAggregationRepository: in Camel 4.0 its INSERT leaves out the version column.
     */
    @Bean
    public JdbcAggregationRepository fulfillmentAggregationRepository(
            DataSource dataSource,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        JdbcAggregationRepository repository =
                new JdbcAggregationRepository(transactionManager, "fulfillment_aggregation", dataSource);
        // Stored exchanges are Java-serialized. Camel's default allow-list has only JDK and Camel
        // classes, so the fulfillment events in our model package are added, and nothing else
        repository.setDeserializationFilter("!java.net.**;java.**;org.apache.camel.**;com.example.orders.model.*;!*");
        return repository;
    }

    /**
     * EIP 1.4: Transactional Client (.transacted()) setup.
     * Connects Camel's .transacted() DSL directly to Spring's PlatformTransactionManager.
     */
    @Bean(name = "PROPAGATION_REQUIRED")
    public org.apache.camel.spring.spi.SpringTransactionPolicy propagationRequired(
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        org.apache.camel.spring.spi.SpringTransactionPolicy policy = new org.apache.camel.spring.spi.SpringTransactionPolicy();
        policy.setTransactionManager(transactionManager);
        policy.setPropagationBehaviorName("PROPAGATION_REQUIRED");
        return policy;
    }

    /**
     * EIP 2.1: Saga Coordinator (.saga()) service, kept in this JVM. Used only when
     * camel.lra.enabled is false; otherwise camel-lra-starter provides the LRA saga service,
     * which keeps sagas in the Narayana coordinator so they outlive the pod that started them.
     */
    @Bean
    @ConditionalOnProperty(name = "camel.lra.enabled", havingValue = "false", matchIfMissing = true)
    public org.apache.camel.saga.CamelSagaService sagaService(org.apache.camel.CamelContext camelContext) throws Exception {
        org.apache.camel.saga.InMemorySagaService sagaService = new org.apache.camel.saga.InMemorySagaService();
        // Starts it with the context; a bean alone is never started, and compensation then has no thread pool
        camelContext.addService(sagaService);
        return sagaService;
    }
}
