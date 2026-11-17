package com.example.orders.repository;

import com.example.orders.model.CustomerNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, String> {

    /**
     * Messages due to be sent, locked for this transaction. Other pods skip them instead of waiting.
     */
    @Query(value = "SELECT * FROM customer_notifications WHERE status = 'PENDING' AND next_attempt_at <= :now "
                 + "ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<CustomerNotification> claimDue(@Param("now") LocalDateTime now);

    /**
     * Puts back messages a pod took but never finished, for example because it stopped.
     */
    @Modifying
    @Query(value = "UPDATE customer_notifications SET status = 'PENDING' WHERE status = 'SENDING' AND claimed_at < :cutoff",
           nativeQuery = true)
    int releaseAbandoned(@Param("cutoff") LocalDateTime cutoff);

    List<CustomerNotification> findByOrderIdOrderByCreatedAtAsc(String orderId);
}
