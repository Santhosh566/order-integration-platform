package com.example.orders.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

/**
 * The application's task executor, which carries the signed-in user onto its threads.
 *
 * Camel's platform-http (Camel 4.22) runs each REST request on a thread of this executor rather
 * than on the servlet thread. Spring Security keeps the user in a thread-local, so without this
 * wrapper every route would see no user. Each task gets the context of the thread that submitted
 * it and clears it afterwards, so a pooled thread never keeps one caller's identity for the next.
 */
@Configuration
public class TaskExecutorConfig {

    /** Replaces Spring Boot's applicationTaskExecutor; spring.task.execution.* still configures the pool. */
    @Bean(name = "applicationTaskExecutor")
    public SecurityContextTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        ThreadPoolTaskExecutor pool = builder.build();
        pool.initialize();
        return new SecurityContextTaskExecutor(pool);
    }

    /** Shuts the pool down with the application. */
    public static class SecurityContextTaskExecutor extends DelegatingSecurityContextAsyncTaskExecutor
            implements DisposableBean {

        private final ThreadPoolTaskExecutor pool;

        SecurityContextTaskExecutor(ThreadPoolTaskExecutor pool) {
            super(pool);
            this.pool = pool;
        }

        @Override
        public void destroy() {
            pool.shutdown();
        }
    }
}
