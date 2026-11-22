package com.example.orders.service;

import com.example.orders.model.DeliveryStatus;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.ItemCheckResult;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.PaymentResult;
import com.example.orders.model.StockMovement;
import com.example.orders.repository.DeliveryAssignmentRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stock movements and the order statuses tied to them.
 *
 * Every write locks the order first and then its stock rows in SKU order, and acts
 * only when the order's status says the stock is really held. That makes each step
 * safe to repeat and safe against a concurrent cancel.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final PaymentGatewayService paymentGatewayService;
    private final DeliveryAssignmentRepository deliveryAssignments;
    private final StockLedger stockLedger;
    private final com.example.orders.warehouse.WarehouseService warehouses;
    private final com.example.orders.warehouse.WarehouseAllocator allocator;

    public InventoryService(InventoryRepository inventoryRepository,
                            OrderRepository orderRepository,
                            OrderStateService orderStateService,
                            PaymentGatewayService paymentGatewayService,
                            DeliveryAssignmentRepository deliveryAssignments,
                            StockLedger stockLedger,
                            com.example.orders.warehouse.WarehouseService warehouses,
                            com.example.orders.warehouse.WarehouseAllocator allocator) {
        this.stockLedger = stockLedger;
        this.warehouses = warehouses;
        this.allocator = allocator;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.paymentGatewayService = paymentGatewayService;
        this.deliveryAssignments = deliveryAssignments;
    }

    /**
     * Checks if a single order item is in stock.
     */
    @Transactional(readOnly = true)
    public ItemCheckResult checkStock(OrderItem item) {
        if (item == null || item.getSku() == null) {
            return new ItemCheckResult("UNKNOWN", 0, 0, false);
        }

        return inventoryRepository.findById(item.getSku())
                .map(inv -> {
                    boolean hasEnough = inv.getAvailableQuantity() >= item.getQuantity();
                    log.info("[INVENTORY] SKU: {}, Requested: {}, Available: {} -> In Stock: {}",
                            inv.getSku(), item.getQuantity(), inv.getAvailableQuantity(), hasEnough);
                    return new ItemCheckResult(inv.getSku(), item.getQuantity(), inv.getAvailableQuantity(), hasEnough);
                })
                .orElseGet(() -> {
                    log.warn("[INVENTORY] SKU: {} NOT FOUND in warehouse catalogue", item.getSku());
                    return new ItemCheckResult(item.getSku(), item.getQuantity(), 0, false);
                });
    }

    /**
     * True when the pipeline should not touch this order, for example because it was
     * cancelled while its event waited in Kafka.
     */
    @Transactional(readOnly = true)
    public boolean isNotPending(String orderId) {
        return orderRepository.findByOrderId(orderId)
                .map(order -> order.getStatus() != OrderStatus.PENDING)
                .orElse(true);
    }

    /**
     * True while the order is reserved and still waiting for its payment.
     */
    @Transactional(readOnly = true)
    public boolean isAwaitingPayment(String orderId) {
        return orderRepository.findByOrderId(orderId)
                .map(order -> order.getStatus() == OrderStatus.INVENTORY_RESERVED)
                .orElse(false);
    }

    /**
     * Reserves every line of the order, or none of them.
     * The stock check before this step is only a quick look; this one decides, under lock.
     *
     * @param sagaId the saga reserving it, which from now on may compensate it
     * @return true when the order is reserved and may continue to payment
     */
    @Transactional
    public boolean reserve(String orderId, String sagaId) {
        Order order = orderStateService.lock(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            log.warn("[INVENTORY] Order {} is {}, nothing to reserve", orderId, order.getStatus());
            return false;
        }

        // Every product row first (sorted, as everywhere): no other order can change these products' stock in any
        // warehouse until this commits, so the warehouse balances read below hold
        Map<String, Integer> quantities = quantitiesBySku(order);
        Map<InventoryItem, Integer> reservations = new LinkedHashMap<>();
        List<String> missingSkus = new ArrayList<>();
        quantities.forEach((sku, quantity) -> inventoryRepository.findForUpdate(sku)
                .ifPresentOrElse(inv -> reservations.put(inv, quantity), () -> missingSkus.add(sku)));

        // The warehouse chosen when it was placed, if it still has everything; else the nearest that has now
        long warehouse = warehouses.of(order);
        if (missingSkus.isEmpty() && !hasAll(warehouse, quantities)) {
            java.util.Optional<Long> other = allocator.nearestWithAll(order);
            if (other.isPresent()) {
                log.info("[INVENTORY] Order {} moves from warehouse {} to {}: the first no longer has everything",
                        orderId, warehouse, other.get());
                warehouse = other.get();
                // Not invoiced yet: its GST is split again from the new warehouse's registration
                allocator.shipFrom(order, warehouse);
            } else {
                quantities.forEach((sku, quantity) -> {
                    if (stockLedger.balance(warehouses.of(order), sku).available() < quantity) {
                        missingSkus.add(sku);
                    }
                });
                if (missingSkus.isEmpty()) {
                    missingSkus.addAll(quantities.keySet());
                }
            }
        }

        if (!missingSkus.isEmpty()) {
            log.warn("[INVENTORY] Rejecting order {} due to insufficient stock for SKUs: {}", orderId, missingSkus);
            orderStateService.moveTo(order, OrderStatus.OUT_OF_STOCK);
            return false;
        }

        long from = warehouse;
        reservations.forEach((inv, quantity) ->
                stockLedger.record(inv, from, StockMovement.Type.RESERVE, -quantity, quantity, orderId, null));
        order.setWarehouseId(from);
        order.setSagaId(sagaId);
        orderStateService.moveTo(order, OrderStatus.INVENTORY_RESERVED);
        return true;
    }

    /**
     * Marks the order as OUT_OF_STOCK in the database when stock check fails.
     */
    @Transactional
    public void markOutOfStock(String orderId, List<String> missingSkus) {
        log.warn("[INVENTORY] Rejecting order {} due to insufficient stock for SKUs: {}", orderId, missingSkus);
        Order order = orderStateService.lock(orderId);
        if (order.getStatus() == OrderStatus.PENDING) {
            orderStateService.moveTo(order, OrderStatus.OUT_OF_STOCK);
        }
    }

    /**
     * Records a successful charge and confirms the order. If the customer cancelled
     * while the gateway was working, the money goes straight back instead.
     *
     * @return true when the order is confirmed and may continue to logistics
     */
    @Transactional
    public boolean confirmPayment(String orderId, PaymentResult payment) {
        Order order = orderStateService.lock(orderId);
        if (order.getStatus() != OrderStatus.INVENTORY_RESERVED) {
            log.warn("[PAYMENT] Order {} became {} during the charge. Refunding.", orderId, order.getStatus());
            // The wallet part was not taken yet, so only the card's charge goes back
            paymentGatewayService.refundCardCharge(order, payment);
            orderRepository.save(order);
            return false;
        }
        // The wallet part is taken now, with the card's charge, so an order that never got this far took nothing
        try {
            paymentGatewayService.recordWalletPayment(order);
        } catch (WalletService.InsufficientBalanceException e) {
            log.warn("[PAYMENT] Order {}: the wallet no longer covers {} ({}). Refunding the card and failing the payment.",
                    orderId, order.getWalletAmount(), e.getMessage());
            paymentGatewayService.refundCardCharge(order, payment);
            releaseReserved(orderId);
            return false;
        }
        paymentGatewayService.recordCharge(order, payment);
        orderStateService.moveTo(order, OrderStatus.CONFIRMED);
        return true;
    }

    /**
     * Cash on delivery: nothing to charge now, so the reserved order is confirmed as it is.
     * The driver collects the money at the door (DeliveryService.deliver).
     *
     * @return true when the order is confirmed and may continue to logistics
     */
    @Transactional
    public boolean confirmCashOnDelivery(String orderId) {
        Order order = orderStateService.lock(orderId);
        if (!order.isCashOnDelivery() || order.getStatus() != OrderStatus.INVENTORY_RESERVED) {
            log.warn("[PAYMENT] Order {} is {} ({}); not confirming it as cash on delivery",
                    orderId, order.getStatus(), order.getPaymentMethod());
            return false;
        }
        log.info("[PAYMENT] Order {} is cash on delivery: {} {} to collect at the door",
                orderId, order.getCurrency(), order.getTotal());
        orderStateService.moveTo(order, OrderStatus.CONFIRMED);
        return true;
    }

    /**
     * EIP: Compensating Action (Saga Pattern)
     * Undoes the saga's work on an order it still holds, when a later step failed or the LRA
     * coordinator timed the saga out. The LRA coordinator does not promise to call compensations
     * in reverse order, so this also refunds a paid order instead of relying on the payment
     * compensation having run first.
     *
     * Does nothing once the order has left the saga (shipped, cancelled, or taken over by
     * recovery), so a late or repeated call is harmless.
     */
    @Transactional
    public void compensateReservation(String orderId, String sagaId) {
        Order order = orderStateService.lock(orderId);
        if (!order.ownedBy(sagaId)) {
            log.warn("[COMPENSATION] Order {} is not in saga {}. Leaving it alone.", orderId, sagaId);
            return;
        }
        if (order.getStatus() != OrderStatus.INVENTORY_RESERVED && order.getStatus() != OrderStatus.CONFIRMED) {
            log.info("[COMPENSATION] Order {} is {}, nothing to undo", orderId, order.getStatus());
            return;
        }
        paymentGatewayService.issueRefund(order);
        releaseReserved(orderId);
    }

    /**
     * Returns held stock. The status shows which step failed: still reserved means the
     * payment never completed, anything later means fulfillment failed.
     */
    @Transactional
    public void releaseReserved(String orderId) {
        Order order = orderStateService.lock(orderId);
        if (!order.getStatus().holdsStock()) {
            log.info("[COMPENSATION] Order {} is {}, no stock to release", orderId, order.getStatus());
            return;
        }

        log.warn("[COMPENSATION - SAGA] Releasing reserved stock for Order {}", orderId);
        returnToAvailable(order);
        orderStateService.moveTo(order, order.getStatus() == OrderStatus.INVENTORY_RESERVED
                ? OrderStatus.PAYMENT_FAILED : OrderStatus.FULFILLMENT_FAILED);
    }

    /**
     * Phase 4.4: Order Cancellation Workflow
     * Cancels an order that has not left the warehouse: stock goes back only if the
     * order held it, and a refund is issued only if the order was paid.
     *
     * @throws com.example.orders.exception.InvalidOrderStateException when the order can no longer be cancelled
     */
    @Transactional
    public Order cancel(String orderId) {
        return cancel(orderId, null);
    }

    /**
     * Cancels the order a person was looking at.
     *
     * @param expectedVersion the version they saw (If-Match), or null for the order as it is now
     * @throws com.example.orders.exception.OrderChangedException when it changed after they opened it
     */
    @Transactional
    public Order cancel(String orderId, Long expectedVersion) {
        Order order = orderStateService.lock(orderId, expectedVersion);
        if (order.getStatus().holdsStock()) {
            log.warn("[CANCELLATION] Restoring stock for Order {}", orderId);
            returnToAvailable(order);
        }
        if (order.getStatus().isCancellable()) {
            paymentGatewayService.issueRefund(order);
        }
        orderStateService.moveTo(order, OrderStatus.CANCELLED);
        // A driver who took it but has not picked it up yet is freed
        deliveryAssignments.findLive(orderId).ifPresent(assignment -> assignment.setStatus(DeliveryStatus.CANCELLED));
        return order;
    }

    /**
     * Goods back from failed deliveries go on the shelf again. They left the reservation at pickup.
     */
    @Transactional
    public void restockReturned(Order order) {
        long warehouse = warehouses.of(order);
        quantitiesBySku(order).forEach((sku, quantity) ->
                inventoryRepository.findForUpdate(sku).ifPresent(inv ->
                        stockLedger.record(inv, warehouse, StockMovement.Type.RETURN, quantity, 0, order.getOrderId(),
                                "Back from failed delivery")));
    }

    /**
     * The goods left the warehouse: the reserved units are gone for good.
     * Available stock already went down when they were reserved.
     */
    @Transactional
    public void commitShipped(Order order) {
        long warehouse = warehouses.of(order);
        quantitiesBySku(order).forEach((sku, quantity) ->
                inventoryRepository.findForUpdate(sku).ifPresent(inv ->
                        stockLedger.record(inv, warehouse, StockMovement.Type.SHIP, 0,
                                -Math.min(quantity, stockLedger.balance(warehouse, sku).reserved()), order.getOrderId(), null)));
    }

    /**
     * Puts a failed order back to PENDING so a dead letter replay can run it again.
     * Orders in any other status are left alone. The order's payment fields describe the
     * current attempt only; earlier charges and refunds stay in the payment history.
     */
    @Transactional
    public void reopenForReplay(String orderId) {
        Order order = orderRepository.findForUpdate(orderId).orElse(null);
        if (order != null && order.getStatus().canMoveTo(OrderStatus.PENDING)) {
            order.setPaymentTransactionId(null);
            order.setPaymentGateway(null);
            order.setRefundedAt(null);
            order.setRefundedTotal(java.math.BigDecimal.ZERO);
            order.setWalletRefunded(java.math.BigDecimal.ZERO);
            order.setRecoveryAttempts(0);
            order.setLastRecoveryAt(null);
            orderStateService.moveTo(order, OrderStatus.PENDING);
        }
    }

    private void returnToAvailable(Order order) {
        long warehouse = warehouses.of(order);
        quantitiesBySku(order).forEach((sku, quantity) ->
                inventoryRepository.findForUpdate(sku).ifPresent(inv ->
                        stockLedger.record(inv, warehouse, StockMovement.Type.RELEASE, quantity,
                                -Math.min(quantity, stockLedger.balance(warehouse, sku).reserved()), order.getOrderId(), null)));
    }

    /** The warehouse is open and holds every line. */
    private boolean hasAll(long warehouse, Map<String, Integer> quantities) {
        return warehouses.get(warehouse).active() && quantities.entrySet().stream()
                .allMatch(e -> stockLedger.balance(warehouse, e.getKey()).available() >= e.getValue());
    }

    /**
     * Sums repeated lines per SKU. The sorted keys give every order the same lock order.
     */
    private static Map<String, Integer> quantitiesBySku(Order order) {
        Map<String, Integer> quantities = new TreeMap<>();
        // Units cancelled before dispatch already went back to stock
        for (OrderItem item : order.getItems()) {
            if (item.getActiveQuantity() > 0) {
                quantities.merge(item.getSku(), item.getActiveQuantity(), Integer::sum);
            }
        }
        return quantities;
    }
}
