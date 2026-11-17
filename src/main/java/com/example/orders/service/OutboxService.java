package com.example.orders.service;

import com.example.orders.model.OutboxEvent;
import com.example.orders.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Database side of the Transactional Outbox. It is a separate bean so that calls
 * from OutboxRoute go through Spring's proxy and @Transactional really applies;
 * without a transaction, FOR UPDATE SKIP LOCKED protects nothing.
 */
@Service("outboxService")
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Atomically selects and claims up to 20 PENDING events using FOR UPDATE SKIP LOCKED.
     * Transitions them to PROCESSING so no other pod picks them up.
     */
    @Transactional
    public List<OutboxEvent> claimPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findPendingEventsWithLock();
        LocalDateTime now = LocalDateTime.now();
        for (OutboxEvent event : events) {
            event.setStatus("PROCESSING");
            event.setClaimedAt(now);
        }
        return outboxEventRepository.saveAll(events);
    }

    /**
     * Recovers any events stuck in PROCESSING for more than 60 seconds (pod crash recovery).
     */
    @Transactional
    public void recoverStaleProcessingEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(60);
        for (OutboxEvent event : outboxEventRepository.findByStatus("PROCESSING")) {
            LocalDateTime claimedAt = event.getClaimedAt() != null ? event.getClaimedAt() : event.getCreatedAt();
            if (claimedAt != null && claimedAt.isBefore(cutoff)) {
                log.warn("[OUTBOX RECOVERY] Resetting stuck event {} (Order: {}) from PROCESSING to PENDING",
                        event.getId(), event.getOrderId());
                event.setStatus("PENDING");
                outboxEventRepository.save(event);
            }
        }
    }

    /**
     * After Kafka confirms the message, mark the outbox row PUBLISHED.
     */
    @Transactional
    public void markPublished(String eventId) {
        if (eventId == null) return;
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus("PUBLISHED");
            event.setPublishedAt(LocalDateTime.now());
            outboxEventRepository.save(event);
            log.info("[OUTBOX] Event {} marked PUBLISHED.", eventId);
        });
    }

    /**
     * Reverts an event to PENDING when Kafka delivery fails.
     */
    @Transactional
    public void markPending(String eventId) {
        if (eventId == null) return;
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus("PENDING");
            outboxEventRepository.save(event);
            log.warn("[OUTBOX] Event {} reverted to PENDING.", eventId);
        });
    }
}
