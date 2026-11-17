package com.example.orders.repository;

import com.example.orders.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for outbox_events.
 * Used by OrderService to save new rows in the same transaction as the order.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    // Used by OutboxRoute to find all rows not yet sent to Kafka
    List<OutboxEvent> findByStatus(String status);

    /**
     * Multi-pod concurrency lock:
     * Selects up to 20 pending rows, locking them exclusively for this transaction.
     * Other pods concurrently polling will automatically skip locked rows without waiting.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC FOR UPDATE SKIP LOCKED LIMIT 20",
        nativeQuery = true
    )
    List<OutboxEvent> findPendingEventsWithLock();
}
