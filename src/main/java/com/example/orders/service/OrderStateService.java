package com.example.orders.service;

import jakarta.persistence.EntityManager;

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
        Order order = orderRepository.findForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        entityManager.refresh(order);
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
