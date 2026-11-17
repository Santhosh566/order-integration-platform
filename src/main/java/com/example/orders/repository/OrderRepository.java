package com.example.orders.repository;

import java.math.BigDecimal;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for orders.
 */
public interface OrderRepository extends JpaRepository<Order, String> {
    Optional<Order> findByOrderId(String orderId);

    /**
     * Loads an order and locks its row, so the pipeline and a cancel cannot change it at the same time.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.orderId = :orderId")
    Optional<Order> findForUpdate(String orderId);

    /**
     * Claims orders the carrier tracker should advance. Rows another pod holds are skipped.
     */
    @Query(value = "SELECT * FROM orders WHERE status IN ('READY_FOR_DISPATCH', 'IN_TRANSIT', 'OUT_FOR_DELIVERY') "
                 + "AND deleted_at IS NULL ORDER BY created_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Order> claimOrdersInTransit();

    /**
     * Claims orders that stopped part way through the pipeline before the cutoff and were not
     * picked up by recovery since then. An order whose message is still waiting in the outbox
     * is not stuck. Rows another pod holds are skipped.
     */
    @Query(value = "SELECT * FROM orders o WHERE o.deleted_at IS NULL "
                 + "AND ((o.status IN ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMED') AND o.status_changed_at < :cutoff) "
                 + "  OR (o.status = 'SHIPPED' AND o.status_changed_at < :releaseCutoff)) "
                 + "AND (o.last_recovery_at IS NULL OR o.last_recovery_at < :cutoff) "
                 + "AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.order_id = o.order_id AND e.status <> 'PUBLISHED') "
                 + "ORDER BY o.status_changed_at LIMIT 20 FOR UPDATE OF o SKIP LOCKED",
           nativeQuery = true)
    List<Order> claimStuckOrders(LocalDateTime cutoff, LocalDateTime releaseCutoff);

    /**
     * Orders waiting for a delivery user that nobody holds yet, oldest first.
     */
    @Query(value = "SELECT * FROM orders o WHERE o.status IN ('READY_FOR_DISPATCH', 'DELIVERY_FAILED') "
                 + "AND o.deleted_at IS NULL "
                 + "AND NOT EXISTS (SELECT 1 FROM delivery_assignments a WHERE a.order_id = o.order_id "
                 + "                AND a.status IN ('ASSIGNED', 'PICKED_UP')) "
                 + "ORDER BY o.status_changed_at LIMIT 100",
           nativeQuery = true)
    List<Order> findAwaitingDriver();

    Optional<Order> findByCustomerIdAndExternalReference(String customerId, String externalReference);

    /**
     * Orders in the given statuses whose id, customer, reference or invoice number contains the
     * text (lower case, already wrapped in %), for the admin order list.
     */
    @Query("select o from Order o where o.status in :statuses and (:text = '%%' "
         + "or lower(o.orderId) like :text or lower(o.customerId) like :text "
         + "or lower(coalesce(o.externalReference, '')) like :text or lower(coalesce(o.invoiceNumber, '')) like :text)")
    Page<Order> search(Collection<OrderStatus> statuses, String text, Pageable pageable);

    @Query("select o.status, count(o) from Order o group by o.status")
    List<Object[]> countByStatus();

    @Query("select o.orderId from Order o where o.status = :status")
    List<String> findIdsByStatus(OrderStatus status);

    // Net of partial refunds, such as returns and goodwill. Items cancelled before dispatch are refunded
    // on a prepaid order; on cash on delivery they were never paid, so they come off separately.
    @Query("select coalesce(sum(o.total - o.refundedTotal - case when o.paymentMethod = com.example.orders.model.Order.PaymentMethod.COD "
            + "then o.cancelledTotal else 0 end), 0) from Order o where o.status in :statuses and o.currency = :currency")
    BigDecimal sumTotalByStatusInAndCurrency(Collection<OrderStatus> statuses, String currency);
}
