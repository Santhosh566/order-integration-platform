package com.example.orders.service;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 4.3: Carrier Tracking Milestone Service
 *
 * Releases booked orders to dispatch, and in development simulates the delivery:
 * READY_FOR_DISPATCH -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED.
 *
 * Progress is read from the database on each poll, so tracking survives a restart,
 * runs on any number of pods, and simply stops seeing an order that was cancelled.
 */
@Service("carrierTrackingService")
public class CarrierTrackingService {

    private static final Logger log = LoggerFactory.getLogger(CarrierTrackingService.class);

    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final InventoryService inventoryService;
    private final boolean autoRelease;
    private final PaymentGatewayService paymentGatewayService;
    private final boolean simulateDelivery;

    public CarrierTrackingService(OrderRepository orderRepository,
                                  OrderStateService orderStateService,
                                  InventoryService inventoryService,
                                  PaymentGatewayService paymentGatewayService,
                                  @Value("${orders.fulfillment.auto-release}") boolean autoRelease,
                                  @Value("${orders.delivery.simulate}") boolean simulateDelivery) {
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.inventoryService = inventoryService;
        this.paymentGatewayService = paymentGatewayService;
        this.autoRelease = autoRelease;
        this.simulateDelivery = simulateDelivery;
    }

    /**
     * Stands in for the warehouse in development: booked orders are released without
     * waiting for PAYMENT_CAPTURED and WAREHOUSE_PACKED. Empty when auto-release is off.
     */
    @Transactional(readOnly = true)
    public List<String> ordersToAutoRelease() {
        return autoRelease ? orderRepository.findIdsByStatus(OrderStatus.SHIPPED) : List.of();
    }

    /**
     * Releases a booked order to the shipping dock.
     *
     * @return false when the order is not waiting for release, so a late or repeated signal changes nothing
     */
    /** Cash on delivery: no payment is captured before dispatch, so the warehouse's signal is enough. */
    @Transactional(readOnly = true)
    public boolean isCashOnDelivery(String orderId) {
        return orderRepository.findByOrderId(orderId).map(Order::isCashOnDelivery).orElse(false);
    }

    @Transactional
    public boolean releaseToDispatch(String orderId) {
        Order order = orderStateService.lock(orderId);
        if (order.getStatus() != OrderStatus.SHIPPED) {
            log.warn("[FULFILLMENT RELEASE] Order {} is {}, ignoring release", orderId, order.getStatus());
            return false;
        }
        orderStateService.moveTo(order, OrderStatus.READY_FOR_DISPATCH);
        return true;
    }

    /**
     * Development stand-in for delivery users: moves each dispatched order one milestone forward.
     * Off by default (orders.delivery.simulate); then delivery users do this through DeliveryService.
     */
    @Transactional
    public void advanceOrdersInTransit() {
        if (!simulateDelivery) {
            return;
        }
        for (Order order : orderRepository.claimOrdersInTransit()) {
            OrderStatus next = nextMilestone(order.getStatus());
            if (next == OrderStatus.IN_TRANSIT) {
                inventoryService.commitShipped(order);
            }
            if (next == OrderStatus.DELIVERED) {
                // The simulated driver collects cash on delivery too
                paymentGatewayService.recordCashCollected(order, "simulated-delivery");
            }
            orderStateService.moveTo(order, next);

            log.info("[CARRIER TRACKING] Order {} is now {} (Carrier: {}, Tracking: {})",
                     order.getOrderId(), next, order.getCarrier(), order.getTrackingNumber());
        }
    }

    private static OrderStatus nextMilestone(OrderStatus status) {
        return switch (status) {
            case READY_FOR_DISPATCH -> OrderStatus.IN_TRANSIT;
            case IN_TRANSIT -> OrderStatus.OUT_FOR_DELIVERY;
            case OUT_FOR_DELIVERY -> OrderStatus.DELIVERED;
            default -> throw new IllegalArgumentException("Order in " + status + " is not on the road");
        };
    }
}
