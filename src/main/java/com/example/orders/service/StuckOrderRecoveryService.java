package com.example.orders.service;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.OutboxEvent;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.OutboxEventRepository;
import org.apache.camel.spi.IdempotentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Finishes or unwinds orders the pipeline left half done. With the LRA coordinator, a saga whose
 * pod stopped is compensated by the coordinator after orders.saga.timeout-ms, before this job
 * looks; this job is the safety net for PENDING orders, SHIPPED orders, and sagas the
 * coordinator could not finish (for example while it was down). With the in-memory saga
 * service, a stopped pod's sagas are gone and this job alone cleans up.
 *
 *   PENDING            the message was lost: publish it again (after the attempts run out: FULFILLMENT_FAILED and the DLQ;
 *                      with no address: FULFILLMENT_FAILED, as nothing was charged)
 *   INVENTORY_RESERVED no charge was recorded: return the stock (PAYMENT_FAILED)
 *   CONFIRMED          the customer paid: ship it (with no address, or after the attempts run out: refund and return the stock)
 *   SHIPPED            past the release deadline without warehouse signals: refund and return the stock
 *
 * With a real payment gateway, an INVENTORY_RESERVED order must be checked with the gateway
 * first, because the app can stop between the charge and recording it.
 */
@Service
public class StuckOrderRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StuckOrderRecoveryService.class);

    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final InventoryService inventoryService;
    private final PaymentGatewayService paymentGatewayService;
    private final OutboxEventRepository outboxEventRepository;
    private final DeadLetterService deadLetterService;
    private final IdempotentRepository kafkaOrderIdRepository;
    private final long stuckAfterMs;
    private final int maxAttempts;
    private final long releaseDeadlineMs;
    private final TransactionTemplate transactionTemplate;

    public StuckOrderRecoveryService(PlatformTransactionManager transactionManager,
                                     OrderRepository orderRepository,
                                     OrderStateService orderStateService,
                                     InventoryService inventoryService,
                                     PaymentGatewayService paymentGatewayService,
                                     OutboxEventRepository outboxEventRepository,
                                     DeadLetterService deadLetterService,
                                     @Qualifier("kafkaOrderIdRepository") IdempotentRepository kafkaOrderIdRepository,
                                     @Value("${orders.recovery.stuck-after-ms}") long stuckAfterMs,
                                     @Value("${orders.recovery.max-attempts}") int maxAttempts,
                                     @Value("${orders.fulfillment.release-deadline-ms}") long releaseDeadlineMs) {
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.inventoryService = inventoryService;
        this.paymentGatewayService = paymentGatewayService;
        this.outboxEventRepository = outboxEventRepository;
        this.deadLetterService = deadLetterService;
        this.kafkaOrderIdRepository = kafkaOrderIdRepository;
        this.stuckAfterMs = stuckAfterMs;
        this.maxAttempts = maxAttempts;
        this.releaseDeadlineMs = releaseDeadlineMs;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Handles one batch of stuck orders. The batch is claimed first, so other pods skip it,
     * then each order is handled in its own transaction: one that fails cannot hold up the rest.
     *
     * @return paid orders to ship, which the caller sends on
     */
    public List<String> recover() {
        LocalDateTime now = LocalDateTime.now();
        List<String> claimed = transactionTemplate.execute(status -> {
            List<String> ids = new ArrayList<>();
            for (Order order : orderRepository.claimStuckOrders(now.minusNanos(stuckAfterMs * 1_000_000),
                                                                now.minusNanos(releaseDeadlineMs * 1_000_000))) {
                order.setRecoveryAttempts(order.getRecoveryAttempts() + 1);
                order.setLastRecoveryAt(now);
                // Recovery decides from here on: a saga the LRA coordinator cancels later must not undo its work
                order.setSagaId(null);
                orderRepository.save(order);
                ids.add(order.getOrderId());
            }
            return ids;
        });

        List<String> toShip = new ArrayList<>();
        for (String orderId : claimed) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    if (handle(orderId)) {
                        toShip.add(orderId);
                    }
                });
            } catch (Exception e) {
                log.error("[RECOVERY] Could not recover order {}: {}", orderId, e.getMessage(), e);
            }
        }
        return toShip;
    }

    /**
     * @return true when the order is paid and should be shipped
     */
    private boolean handle(String orderId) {
        Order order = orderStateService.lock(orderId);
        boolean giveUp = order.getRecoveryAttempts() > maxAttempts;
        log.warn("[RECOVERY] Order {} has been {} since {} (attempt {})",
                orderId, order.getStatus(), order.getStatusChangedAt(), order.getRecoveryAttempts());

        switch (order.getStatus()) {
            case PENDING -> {
                if (order.getShippingAddress() == null) {
                    // From before addresses existed: running it again would charge for a delivery with no destination
                    log.error("[RECOVERY] Order {} has no delivery address. Closing it without a charge.", orderId);
                    orderStateService.moveTo(order, OrderStatus.FULFILLMENT_FAILED);
                } else if (giveUp) {
                    failUnprocessed(order);
                } else {
                    publishAgain(order);
                }
            }
            case INVENTORY_RESERVED -> inventoryService.releaseReserved(orderId);
            // The warehouse never released it: the customer is not left waiting for goods that will not come
            case SHIPPED -> abandonPaidOrder(orderId);
            case CONFIRMED -> {
                // Orders from before addresses existed have nowhere to go
                if (giveUp || order.getShippingAddress() == null) {
                    abandonPaidOrder(orderId);
                } else {
                    return true;
                }
            }
            // It moved on after being claimed
            default -> { }
        }
        return false;
    }

    /**
     * A paid order that cannot be shipped: return its stock and refund the customer.
     */
    @Transactional
    public void abandonPaidOrder(String orderId) {
        Order order = orderStateService.lock(orderId);
        if (order.getStatus() != OrderStatus.CONFIRMED && order.getStatus() != OrderStatus.SHIPPED) {
            return;
        }
        log.error("[RECOVERY] Giving up on shipping paid order {}. Refunding.", orderId);
        // Refund while the order still shows as paid: old orders have no payment id to go by
        paymentGatewayService.issueRefund(order);
        inventoryService.releaseReserved(orderId);
    }

    private void publishAgain(Order order) {
        log.warn("[RECOVERY] Publishing order {} to the pipeline again", order.getOrderId());
        // Otherwise the consumer would drop the new message as a duplicate of the lost one
        kafkaOrderIdRepository.remove(order.getOrderId());
        outboxEventRepository.save(OutboxEvent.from(order));
    }

    private void failUnprocessed(Order order) {
        log.error("[RECOVERY] Order {} never got through the pipeline. Moving it to the DLQ.", order.getOrderId());
        String payload = OutboxEvent.from(order).getPayload();
        orderStateService.moveTo(order, OrderStatus.FULFILLMENT_FAILED);
        deadLetterService.recordStuckOrder(order.getOrderId(), payload,
                "Not processed after " + maxAttempts + " recovery attempts");
    }
}
