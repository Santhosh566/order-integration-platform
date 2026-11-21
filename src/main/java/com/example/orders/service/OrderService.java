package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.model.Order;
import com.example.orders.model.OutboxEvent;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.OrderStatusChanged;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.OutboxEventRepository;
import org.apache.camel.Body;
import org.apache.camel.Header;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Order business logic called from the Camel routes.
 *
 * Step 4 change: prepare() now also writes an OutboxEvent row in the same
 * @Transactional block so the order and the "needs to be published" record are
 * always consistent — even if the app crashes before Kafka is reached.
 */
@Service("orderService")
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final InventoryService inventoryService;
    private final OrderStateService orderStateService;
    private final CustomerService customerService;
    private final ApplicationEventPublisher eventPublisher;
    private final CartService carts;

    public OrderService(OrderRepository orderRepository,
                        OutboxEventRepository outboxEventRepository,
                        InventoryService inventoryService,
                        OrderStateService orderStateService,
                        CustomerService customerService,
                        ApplicationEventPublisher eventPublisher,
                        CartService carts) {
        this.orderRepository       = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.inventoryService      = inventoryService;
        this.orderStateService     = orderStateService;
        this.customerService       = customerService;
        this.eventPublisher        = eventPublisher;
        this.carts                 = carts;
    }

    /**
     * Fills in what a new order needs before it is stored, then records an
     * outbox event so the creation is eventually published to Kafka.
     *
     * Both writes happen inside the JPA transaction opened by the Camel JPA
     * component — if either fails, both are rolled back.
     */
    @Transactional
    public Order prepare(@Body Order order, @Header("orderType") String orderType) {
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(OrderStatus.PENDING);
        order.setStatusChangedAt(now);
        order.setOrderType(orderType);
        order.setCreatedAt(now);
        if (order.getShippingAddress() == null) {
            order.setShippingAddress(customerService.defaultAddress(order.getCustomerId())
                    .map(ShippingAddress::new)
                    .orElseThrow(() -> new InvalidOrderException("The customer has no default address")));
        }

        // Save order first so it has its ID set
        Order savedOrder = orderRepository.save(order);

        // Write the outbox row in the SAME transaction
        OutboxEvent event = OutboxEvent.from(savedOrder);
        outboxEventRepository.save(event);

        // The ordered products leave the customer's cart; the order row must exist first (carts.order_id)
        orderRepository.flush();
        carts.orderPlaced(savedOrder);

        eventPublisher.publishEvent(new OrderStatusChanged(savedOrder));
        return savedOrder;
    }

    /**
     * Returns the order, or null when there is none with this id.
     */
    public Order findById(@Header("id") String orderId) {
        return orderRepository.findById(orderId).orElse(null);
    }

    /**
     * Cancels an order, or refuses the move. Returns null when there is no such order.
     * Cancelling runs the full workflow so stock and payment stay correct, and an order
     * only reaches CONFIRMED through a real payment, so that request is refused.
     */
    @Transactional
    public Order updateStatus(@Header("id") String orderId, @Header("newStatus") String newStatus) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return null;
        }
        OrderStatus next = OrderStatus.valueOf(newStatus);
        if (next == OrderStatus.CANCELLED) {
            return inventoryService.cancel(orderId);
        }
        throw new InvalidOrderStateException("Order " + orderId + " can only be confirmed by a successful payment");
    }

    /**
     * Hides a finished, unfulfilled order. Returns false when there is no such order.
     * The row stays for the financial record, so its id cannot be reused.
     */
    @Transactional
    public boolean delete(@Header("id") String orderId) {
        if (orderRepository.findById(orderId).isEmpty()) {
            return false;
        }
        Order order = orderStateService.lock(orderId);
        if (!order.getStatus().isFinishedUnfulfilled()) {
            throw new InvalidOrderStateException("Order " + orderId + " is " + order.getStatus() + " and cannot be deleted");
        }
        order.setDeletedAt(LocalDateTime.now());
        orderRepository.save(order);
        return true;
    }
}
