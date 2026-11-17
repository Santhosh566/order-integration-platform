package com.example.orders.repository;

import com.example.orders.model.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for dead_letter_events.
 */
@Repository
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    /**
     * Lists all quarantined events, newest failure first.
     */
    List<DeadLetterEvent> findAllByOrderByFailedAtDesc();

    /**
     * Lists only events that have not yet been replayed.
     */
    List<DeadLetterEvent> findByReplayedFalseOrderByFailedAtDesc();

    /** Dead letters still waiting for an operator. */
    long countByReplayedFalseAndDismissedAtIsNull();
}
