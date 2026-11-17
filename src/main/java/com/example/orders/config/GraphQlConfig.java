package com.example.orders.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Limits on what one GraphQL request may ask for, so nobody can send a query that makes the server
 * work for minutes (products → related → related → …). Page sizes are capped in CatalogueSearch.
 */
@Configuration
public class GraphQlConfig {

    @Bean
    MaxQueryDepthInstrumentation maxQueryDepth() {
        return new MaxQueryDepthInstrumentation(8);
    }

    @Bean
    MaxQueryComplexityInstrumentation maxQueryComplexity() {
        return new MaxQueryComplexityInstrumentation(400);
    }
}
