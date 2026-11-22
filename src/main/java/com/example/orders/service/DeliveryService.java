package com.example.orders.service;

import com.example.orders.money.Money;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.AppUser;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.DeliveryAssignment;
import com.example.orders.model.DeliveryStatus;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.Role;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.DeliveryAssignmentRepository;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.ReturnPickupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The delivery workflow. A delivery user takes a waiting order, picks it up, sets out, and
 * hands it over against the customer's one-time code:
 *
 *   READY_FOR_DISPATCH -take-> (assigned) -pickup-> IN_TRANSIT -out-> OUT_FOR_DELIVERY -code-> DELIVERED
 *
 * A failed attempt puts the order at DELIVERY_FAILED, where any driver can take it again, or
 * an admin returns it to stock and refunds it. Every step locks the order row first, so two
 * drivers cannot both take it; a database index backs that up.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    static final int MAX_CODE_ATTEMPTS = 5;
    static final String OWN_FLEET = "In-house delivery";

    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final DeliveryAssignmentRepository assignments;
    private final AppUserRepository users;
    private final InventoryService inventoryService;
    private final PaymentGatewayService paymentGatewayService;
    private final DeliveryCodeGenerator codeGenerator;
    private final ReturnPickupRepository returnPickups;
    private final CustomerNotificationService notifications;
    private final com.example.orders.delivery.DriverPay pay;
    private final com.example.orders.warehouse.WarehouseService warehouses;

    public DeliveryService(OrderRepository orderRepository,
                           OrderStateService orderStateService,
                           DeliveryAssignmentRepository assignments,
                           AppUserRepository users,
                           InventoryService inventoryService,
                           PaymentGatewayService paymentGatewayService,
                           DeliveryCodeGenerator codeGenerator,
                           CustomerNotificationService notifications,
                           ReturnPickupRepository returnPickups,
                           com.example.orders.delivery.DriverPay pay,
                           com.example.orders.warehouse.WarehouseService warehouses) {
        this.warehouses = warehouses;
        this.returnPickups = returnPickups;
        this.pay = pay;
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.assignments = assignments;
        this.users = users;
        this.inventoryService = inventoryService;
        this.paymentGatewayService = paymentGatewayService;
        this.codeGenerator = codeGenerator;
        this.notifications = notifications;
    }

    /** Orders nobody has taken, from the warehouse the driver works from. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> available(String driverId) {
        long home = warehouses.homeOf(driverId);
        return available().stream().filter(row -> Long.valueOf(home).equals(row.get("warehouseId"))).toList();
    }

    /**
     * Orders nobody has taken, from every warehouse (the admin's board). Only the area is shown: the name, phone
     * and street are for the driver who takes the order.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> available() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Order order : orderRepository.findAwaitingDriver()) {
            ShippingAddress address = order.getShippingAddress();
            // Orders from before addresses existed cannot be delivered
            if (address == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderId", order.getOrderId());
            row.put("version", order.getVersion());
            row.put("status", order.getStatus());
            long from = warehouses.of(order);
            row.put("warehouseId", from);
            row.put("warehouse", warehouses.codeOf(from));
            row.put("city", address.getCity());
            row.put("state", address.getState());
            row.put("postalCode", address.getPostalCode());
            row.put("itemCount", order.getItems().stream().mapToInt(OrderItem::getQuantity).sum());
            row.put("waitingSince", order.getStatusChangedAt());
            putCheckout(row, order);
            result.add(row);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(String driverId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeliveryAssignment assignment : assignments.findLiveFor(driverId)) {
            orderRepository.findById(assignment.getOrderId())
                    .ifPresent(order -> result.add(details(order, assignment)));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> take(String driverId, String orderId) {
        Order order = orderStateService.lock(orderId);
        requireAwaitingDriver(order);
        requireSameWarehouse(order, driverId, true);
        if (assignments.findLive(orderId).isPresent()) {
            throw new InvalidOrderStateException("Order " + orderId + " was already taken by another driver");
        }
        DeliveryAssignment assignment = assignments.saveAndFlush(newAttempt(order, driverId));
        log.info("[DELIVERY] {} took order {} (attempt {})", driverId, orderId, assignment.getAttemptNumber());
        return details(order, assignment);
    }

    /**
     * An admin gives the order to a driver, taking it from another one who has not picked it up yet.
     */
    @Transactional
    public Map<String, Object> assign(String orderId, String driverId) {
        return assign(orderId, driverId, null);
    }

    /** @param expectedVersion the order version the admin saw (If-Match), or null */
    @Transactional
    public Map<String, Object> assign(String orderId, String driverId, Long expectedVersion) {
        AppUser driver = users.findById(driverId)
                .filter(u -> u.isActive() && u.getRoles().contains(Role.DELIVERY))
                .orElseThrow(() -> new InvalidOrderException(driverId + " is not an active delivery user"));
        Order order = orderStateService.lock(orderId, expectedVersion);
        requireAwaitingDriver(order);
        requireSameWarehouse(order, driver.getUserId(), false);
        assignments.findLive(orderId).ifPresent(current -> {
            if (current.getStatus() == DeliveryStatus.PICKED_UP) {
                throw new InvalidOrderStateException("Order " + orderId + " is already with " + current.getDeliveryUserId());
            }
            current.setStatus(DeliveryStatus.REASSIGNED);
            // Freed first: only one live attempt per order is allowed
            assignments.saveAndFlush(current);
        });
        DeliveryAssignment assignment = assignments.saveAndFlush(newAttempt(order, driver.getUserId()));
        log.info("[DELIVERY] Admin assigned order {} to {}", orderId, driverId);
        return details(order, assignment);
    }

    /**
     * A driver collects parcels at their own warehouse: an order packed in another one is not theirs to take or be
     * given. (409 for the driver taking it, 400 for an admin assigning it.)
     */
    private void requireSameWarehouse(Order order, String driverId, boolean byDriver) {
        long from = warehouses.of(order);
        long home = warehouses.homeOf(driverId);
        if (from != home) {
            String message = "Order " + order.getOrderId() + " ships from " + warehouses.codeOf(from) + ", and "
                    + (byDriver ? "you work" : driverId + " works") + " from " + warehouses.codeOf(home);
            throw byDriver ? new InvalidOrderStateException(message) : new InvalidOrderException(message);
        }
    }

    @Transactional
    public Map<String, Object> pickUp(String driverId, String orderId) {
        Order order = orderStateService.lock(orderId);
        DeliveryAssignment assignment = heldBy(driverId, orderId, DeliveryStatus.ASSIGNED);
        if (order.getStatus() == OrderStatus.READY_FOR_DISPATCH) {
            // The goods leave the warehouse; a later attempt has already done this
            inventoryService.commitShipped(order);
        } else if (order.getStatus() != OrderStatus.DELIVERY_FAILED) {
            throw new InvalidOrderStateException("Order " + orderId + " is " + order.getStatus() + " and cannot be picked up");
        }
        order.setCarrier(OWN_FLEET);
        orderStateService.moveTo(order, OrderStatus.IN_TRANSIT);
        assignment.setStatus(DeliveryStatus.PICKED_UP);
        assignment.setPickedUpAt(LocalDateTime.now());
        return details(order, assignment);
    }

    /**
     * The driver sets out. The customer gets a new one-time code; only its hash is stored.
     */
    @Transactional
    public Map<String, Object> outForDelivery(String driverId, String orderId) {
        Order order = orderStateService.lock(orderId);
        DeliveryAssignment assignment = heldBy(driverId, orderId, DeliveryStatus.PICKED_UP);
        orderStateService.moveTo(order, OrderStatus.OUT_FOR_DELIVERY);
        String code = codeGenerator.next();
        assignment.setDeliveryCodeHash(hash(orderId, code));
        assignment.setCodeAttempts(0);

        // Saved with this change and sent afterwards; the text is removed from the database once sent
        notifications.queue(CustomerNotification.secret(orderId, order.getCustomerId(),
                "Your order " + orderId + " is out for delivery",
                "Your order " + orderId + " is out for delivery today. Give the driver this code when it arrives: "
                        + code + ". Never share it before you have the parcel."
                        + (order.isCashOnDelivery() && order.getPaymentTransactionId() == null
                            ? "\n\nCash on delivery: please keep " + Money.format(order.amountDue(), order.getCurrency())
                                + " ready. Exact change helps the driver."
                            : "")));
        return details(order, assignment);
    }

    /**
     * Wrong codes are counted even though the request fails, so the code cannot be guessed.
     */
    @Transactional(noRollbackFor = InvalidOrderException.class)
    public Map<String, Object> deliver(String driverId, String orderId, String code) {
        return deliver(driverId, orderId, code, false);
    }

    /**
     * Hands the parcel over against the customer's code. A cash-on-delivery order also needs the
     * driver to confirm the cash was collected; that is checked first, so no code attempt is used up.
     */
    @Transactional(noRollbackFor = InvalidOrderException.class)
    public Map<String, Object> deliver(String driverId, String orderId, String code, boolean cashCollected) {
        Order order = orderStateService.lock(orderId);
        DeliveryAssignment assignment = heldBy(driverId, orderId, DeliveryStatus.PICKED_UP);
        if (order.getStatus() != OrderStatus.OUT_FOR_DELIVERY || assignment.getDeliveryCodeHash() == null) {
            throw new InvalidOrderStateException("Order " + orderId + " is not out for delivery");
        }
        if (assignment.getCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            throw new InvalidOrderStateException("Too many wrong codes for order " + orderId + ". Mark the delivery as failed.");
        }
        if (order.isCashOnDelivery() && order.getPaymentTransactionId() == null && !cashCollected) {
            throw new InvalidOrderException("Collect " + Money.format(order.amountDue(), order.getCurrency())
                    + " in cash before handing over the parcel");
        }
        if (code == null || !MessageDigest.isEqual(
                hash(orderId, code.trim()).getBytes(StandardCharsets.UTF_8),
                assignment.getDeliveryCodeHash().getBytes(StandardCharsets.UTF_8))) {
            assignment.setCodeAttempts(assignment.getCodeAttempts() + 1);
            assignments.save(assignment);
            throw new InvalidOrderException("Wrong delivery code");
        }
        assignment.setStatus(DeliveryStatus.DELIVERED);
        assignment.setDeliveredAt(LocalDateTime.now());
        assignment.setPayAmount(pay.delivery());
        assignment.setDeliveryCodeHash(null);
        paymentGatewayService.recordCashCollected(order, driverId);
        orderStateService.moveTo(order, OrderStatus.DELIVERED);
        log.info("[DELIVERY] {} delivered order {}", driverId, orderId);
        return details(order, assignment);
    }

    /**
     * The attempt did not work out. Before pickup the order simply goes back on the list;
     * after it, the goods return to the hub and the order waits at DELIVERY_FAILED.
     */
    @Transactional
    public Map<String, Object> fail(String driverId, String orderId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new InvalidOrderException("A reason of 1 to 255 characters is required");
        }
        Order order = orderStateService.lock(orderId);
        DeliveryAssignment assignment = assignments.findLive(orderId)
                .filter(a -> a.getDeliveryUserId().equals(driverId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        boolean visited = assignment.getStatus() == DeliveryStatus.PICKED_UP;
        if (visited) {
            orderStateService.moveTo(order, OrderStatus.DELIVERY_FAILED);
        }
        assignment.setPayAmount(pay.failed(visited));
        assignment.setStatus(DeliveryStatus.FAILED);
        assignment.setFailedAt(LocalDateTime.now());
        assignment.setFailureReason(reason.trim());
        assignment.setDeliveryCodeHash(null);
        log.warn("[DELIVERY] Attempt {} for order {} failed: {}", assignment.getAttemptNumber(), orderId, reason);
        return details(order, assignment);
    }

    /**
     * After failed deliveries an admin gives up: the goods go back on the shelf and the customer is refunded.
     */
    @Transactional
    public Order returnToStock(String orderId) {
        return returnToStock(orderId, null);
    }

    /** @param expectedVersion the order version the admin saw (If-Match), or null */
    @Transactional
    public Order returnToStock(String orderId, Long expectedVersion) {
        Order order = orderStateService.lock(orderId, expectedVersion);
        if (order.getStatus() != OrderStatus.DELIVERY_FAILED) {
            throw new InvalidOrderStateException("Only an order whose delivery failed can be returned; " + orderId + " is " + order.getStatus());
        }
        inventoryService.restockReturned(order);
        paymentGatewayService.issueRefund(order);
        orderStateService.moveTo(order, OrderStatus.RETURNED);
        return order;
    }

    /**
     * Active delivery users with how many deliveries each holds now, for the admin's assign list.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> drivers() {
        Map<String, Long> load = new java.util.HashMap<>();
        for (DeliveryAssignment live : assignments.findAllLive()) {
            load.merge(live.getDeliveryUserId(), 1L, Long::sum);
        }
        // Return pickups count as work in hand too
        returnPickups.findAllLive().forEach(p -> load.merge(p.getDeliveryUserId(), 1L, Long::sum));
        return users.findAll().stream()
                .filter(u -> u.isActive() && u.getRoles().contains(Role.DELIVERY))
                .sorted(java.util.Comparator.comparing(AppUser::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", u.getUserId());
                    row.put("fullName", u.getFullName());
                    row.put("phone", u.getPhone());
                    row.put("activeDeliveries", load.getOrDefault(u.getUserId(), 0L));
                    long home = warehouses.homeOf(u.getUserId());
                    row.put("warehouseId", home);
                    row.put("warehouse", warehouses.codeOf(home));
                    return row;
                })
                .toList();
    }

    /**
     * Deliveries someone holds now, oldest first: assigned but not yet collected, or on the road.
     * These are not on the waiting list, so this is where an admin finds one to reassign.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> inProgress() {
        Map<String, String> names = new java.util.HashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DeliveryAssignment assignment : assignments.findAllLive()) {
            orderRepository.findById(assignment.getOrderId()).ifPresent(order -> {
                ShippingAddress address = order.getShippingAddress();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", order.getOrderId());
                row.put("version", order.getVersion());
                row.put("status", order.getStatus());
                row.put("deliveryStatus", assignment.getStatus());
                row.put("attempt", assignment.getAttemptNumber());
                row.put("deliveryUserId", assignment.getDeliveryUserId());
                row.put("deliveryUserName", names.computeIfAbsent(assignment.getDeliveryUserId(),
                        id -> users.findById(id).map(AppUser::getFullName).orElse(id)));
                row.put("assignedAt", assignment.getAssignedAt());
                row.put("pickedUpAt", assignment.getPickedUpAt());
                row.put("city", address != null ? address.getCity() : null);
                row.put("state", address != null ? address.getState() : null);
                row.put("postalCode", address != null ? address.getPostalCode() : null);
                result.add(row);
            });
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<DeliveryAssignment> attempts(String orderId) {
        return assignments.findByOrderIdOrderByAttemptNumberAsc(orderId);
    }

    private DeliveryAssignment newAttempt(Order order, String driverId) {
        return new DeliveryAssignment(order.getOrderId(), driverId, (int) assignments.countByOrderId(order.getOrderId()) + 1);
    }

    private static void requireAwaitingDriver(Order order) {
        if (!order.getStatus().isAwaitingDriver()) {
            throw new InvalidOrderStateException("Order " + order.getOrderId() + " is " + order.getStatus() + " and is not waiting for a driver");
        }
        if (order.getShippingAddress() == null) {
            throw new InvalidOrderStateException("Order " + order.getOrderId() + " has no delivery address");
        }
    }

    /** The driver's live attempt for the order. Another driver's order answers as missing. */
    private DeliveryAssignment heldBy(String driverId, String orderId, DeliveryStatus expected) {
        DeliveryAssignment assignment = assignments.findLive(orderId)
                .filter(a -> a.getDeliveryUserId().equals(driverId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (assignment.getStatus() != expected) {
            throw new InvalidOrderStateException("Order " + orderId + " is " + assignment.getStatus() + ", not " + expected);
        }
        return assignment;
    }

    private Map<String, Object> details(Order order, DeliveryAssignment assignment) {
        ShippingAddress address = order.getShippingAddress();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orderId", order.getOrderId());
        row.put("version", order.getVersion());
        row.put("status", order.getStatus());
        row.put("deliveryStatus", assignment.getStatus());
        row.put("attempt", assignment.getAttemptNumber());
        row.put("recipientName", address != null ? address.getRecipientName() : null);
        row.put("recipientPhone", address != null ? address.getRecipientPhone() : null);
        row.put("address", address);
        // Names let the driver check the parcel; old orders may not have one, so no Map.of (it refuses nulls)
        // Lines cancelled before dispatch are not in the parcel
        row.put("items", order.getItems().stream().filter(i -> i.getActiveQuantity() > 0).map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sku", i.getSku());
            item.put("name", i.getProductName());
            item.put("quantity", i.getActiveQuantity());
            return item;
        }).toList());
        // After this many wrong codes the delivery can only be marked failed
        row.put("codeAttemptsLeft", Math.max(0, MAX_CODE_ATTEMPTS - assignment.getCodeAttempts()));
        putCheckout(row, order);
        return row;
    }

    /**
     * What the driver must know before setting out: the booked day and time, and the cash to collect
     * (null when the order is prepaid or the cash was already collected).
     */
    private static void putCheckout(Map<String, Object> row, Order order) {
        row.put("paymentMethod", order.getPaymentMethod());
        row.put("amountToCollect", order.isCashOnDelivery() && order.getPaymentTransactionId() == null ? order.amountDue() : null);
        row.put("currency", order.getCurrency());
        row.put("deliveryDate", order.getDeliveryDate());
        row.put("deliveryWindow", order.getDeliveryWindow());
    }

    /**
     * A one-time code has only a million values, so a slow hash adds little; tying it to the order
     * keeps a copied hash useless for any other delivery.
     */
    /** Also used for return pickups, scoped by the return id instead of the order id. */
    static String hash(String orderId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((orderId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
