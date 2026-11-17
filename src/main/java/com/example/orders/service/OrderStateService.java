package com.example.orders.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import com.example.orders.exception.OrderChangedException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.OrderStatusChanged;
import com.example.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The single place order statuses change. Callers hold a transaction and the
 * order's row lock, so a status decision cannot be overtaken by a concurrent cancel.
 */
@Service
public class OrderStateService {

    private static final Logger log = LoggerFactory.getLogger(OrderStateService.class);

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final EntityManager entityManager;

    public OrderStateService(OrderRepository orderRepository, ApplicationEventPublisher eventPublisher,
                             EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    /**
     * Locks the order's row and returns it as committed now. If this transaction loaded the order
     * before, the query hands back that earlier copy, which can miss what another transaction
     * committed while this one waited for the lock; saving it would undo that change (a lost update:
     * an order moved back from IN_TRANSIT to READY_FOR_DISPATCH and was then shipped twice).
     * So it is read again, under the lock.
     */
    public Order lock(String orderId) {
        // The row is locked without loading the entity: with @Version, a locking query that meets an
        // older copy already held by this transaction fails ("conflicting version") instead of
        // returning it. Once the lock is held, the copy is read again and is then current.
        boolean exists = !entityManager.createNativeQuery(
                        "select 1 from orders where order_id = ?1 and deleted_at is null for update")
                .setParameter(1, orderId)
                .getResultList().isEmpty();
        Order order = exists ? entityManager.find(Order.class, orderId) : null;
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        entityManager.refresh(order);
        return order;
    }

    /**
     * Locks the order for an action a person took on the copy they were shown. When they say which
     * version that was (If-Match) and the order has changed since, nothing is done: they are told
     * what it is now and decide again. The check runs under the row lock, so nothing can slip in
     * between it and the change.
     * <p>
     * The version is raised even when the action itself leaves the order's columns alone (a driver
     * assigned, a goodwill refund recorded elsewhere), so whoever else has the order open learns
     * that something happened to it.
     *
     * @param expectedVersion the version they saw, or null to act on the order as it is now
     * @throws OrderChangedException when it is no longer that version
     */
    public Order lock(String orderId, Long expectedVersion) {
        Order order = lock(orderId);
        if (expectedVersion != null && !expectedVersion.equals(order.getVersion())) {
            log.info("[ORDER VERSION] Order {} is at version {}; the request was made on version {}",
                    orderId, order.getVersion(), expectedVersion);
            throw new OrderChangedException(order, expectedVersion);
        }
        entityManager.lock(order, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return order;
    }

    public void moveTo(Order order, OrderStatus next) {
        OrderStatus previous = order.getStatus();
        order.moveTo(next);
        orderRepository.save(order);
        eventPublisher.publishEvent(new OrderStatusChanged(order));
        log.info("[ORDER STATUS] Order {}: {} -> {}", order.getOrderId(), previous, next);
    }
}
