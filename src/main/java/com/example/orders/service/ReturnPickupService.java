package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.NotFoundException;
import com.example.orders.model.AppUser;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.ReturnItem;
import com.example.orders.model.ReturnPickup;
import com.example.orders.model.ReturnRequest;
import com.example.orders.model.Role;
import com.example.orders.model.ShippingAddress;
import com.example.orders.repository.AppUserRepository;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.ReturnPickupRepository;
import com.example.orders.repository.ReturnRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collecting approved returns from customers: the reverse of a delivery, done by the same
 * delivery users. The customer confirms the hand-over with a one-time code; the refund follows
 * once the goods are inspected at the hub (ReturnService.receive).
 */
@Service
public class ReturnPickupService {

    private static final Logger log = LoggerFactory.getLogger(ReturnPickupService.class);
    static final int MAX_CODE_ATTEMPTS = DeliveryService.MAX_CODE_ATTEMPTS;

    private final ReturnRequestRepository returns;
    private final ReturnPickupRepository pickups;
    private final AppUserRepository users;
    private final OrderStateService orderStateService;
    private final DeliveryCodeGenerator codeGenerator;
    private final CustomerNotificationService notifications;
    private final OrderRepository orders;

    private final com.example.orders.delivery.DriverPay pay;
    private final com.example.orders.warehouse.WarehouseService warehouses;

    public ReturnPickupService(ReturnRequestRepository returns, ReturnPickupRepository pickups, AppUserRepository users,
                               OrderStateService orderStateService, DeliveryCodeGenerator codeGenerator,
                               CustomerNotificationService notifications, OrderRepository orders,
                               com.example.orders.delivery.DriverPay pay,
                               com.example.orders.warehouse.WarehouseService warehouses) {
        this.warehouses = warehouses;
        this.pay = pay;
        this.returns = returns;
        this.pickups = pickups;
        this.users = users;
        this.orderStateService = orderStateService;
        this.codeGenerator = codeGenerator;
        this.notifications = notifications;
        this.orders = orders;
    }

    /** Returned goods go to the warehouse that shipped them, so its drivers collect them. */
    private void requireSameWarehouse(ReturnRequest ret, String driverId, boolean byDriver) {
        Order order = orders.findById(ret.getOrderId()).orElse(null);
        if (order == null) {
            return;
        }
        long to = warehouses.of(order);
        long home = warehouses.homeOf(driverId);
        if (to != home) {
            String message = "Return " + ret.getReturnId() + " goes back to " + warehouses.codeOf(to) + ", and "
                    + (byDriver ? "you work" : driverId + " works") + " from " + warehouses.codeOf(home);
            throw byDriver ? new InvalidOrderStateException(message) : new com.example.orders.exception.InvalidOrderException(message);
        }
    }

    // ── Lists ──────────────────────────────────────────────────────────────────

    /** Returns waiting to be collected that go back to the warehouse the driver works from. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> available(String driverId) {
        long home = warehouses.homeOf(driverId);
        return available().stream().filter(row -> Long.valueOf(home).equals(row.get("warehouseId"))).toList();
    }

    /** Approved returns nobody is collecting yet, oldest approval first, every warehouse. Area only, like orders. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> available() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReturnRequest ret : returns.findByStatusOrderByRequestedAt(ReturnRequest.Status.APPROVED)) {
            if (pickups.findLive(ret.getReturnId()).isPresent()) {
                continue;
            }
            Order order = orders.findById(ret.getOrderId()).orElse(null);
            ShippingAddress address = order == null ? null : order.getShippingAddress();
            if (address == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("returnId", ret.getReturnId());
            row.put("orderId", ret.getOrderId());
            // Back to the warehouse that shipped it
            long to = warehouses.of(order);
            row.put("warehouseId", to);
            row.put("warehouse", warehouses.codeOf(to));
            row.put("city", address.getCity());
            row.put("state", address.getState());
            row.put("postalCode", address.getPostalCode());
            row.put("itemCount", ret.getItems().stream().mapToInt(ReturnItem::getQuantity).sum());
            row.put("approvedAt", ret.getDecidedAt());
            row.put("attempts", pickups.countByReturnId(ret.getReturnId()));
            result.add(row);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> mine(String driverId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReturnPickup pickup : pickups.findLiveFor(driverId)) {
            returns.findById(pickup.getReturnId()).ifPresent(ret -> result.add(details(ret, pickup)));
        }
        return result;
    }

    /** Pickups someone holds now, for the admin's board. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> inProgress() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReturnPickup pickup : pickups.findAllLive()) {
            returns.findById(pickup.getReturnId()).ifPresent(ret -> {
                Order order = orders.findById(ret.getOrderId()).orElse(null);
                ShippingAddress address = order == null ? null : order.getShippingAddress();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("returnId", ret.getReturnId());
                row.put("orderId", ret.getOrderId());
                row.put("pickupStatus", pickup.getStatus());
                row.put("attempt", pickup.getAttemptNumber());
                row.put("deliveryUserId", pickup.getDeliveryUserId());
                row.put("deliveryUserName", users.findById(pickup.getDeliveryUserId())
                        .map(AppUser::getFullName).orElse(pickup.getDeliveryUserId()));
                row.put("assignedAt", pickup.getAssignedAt());
                row.put("startedAt", pickup.getStartedAt());
                row.put("city", address != null ? address.getCity() : null);
                row.put("state", address != null ? address.getState() : null);
                row.put("postalCode", address != null ? address.getPostalCode() : null);
                result.add(row);
            });
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<ReturnPickup> attempts(String returnId) {
        return pickups.findByReturnIdOrderByAttemptNumberAsc(returnId);
    }

    // ── Driver steps ───────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> take(String driverId, String returnId) {
        ReturnRequest ret = lockApproved(returnId);
        requireSameWarehouse(ret, driverId, true);
        if (pickups.findLive(returnId).isPresent()) {
            throw new InvalidOrderStateException("Return " + returnId + " is already being collected");
        }
        ReturnPickup pickup = pickups.saveAndFlush(newAttempt(returnId, driverId));
        log.info("[PICKUP] {} took return {}", driverId, returnId);
        return details(ret, pickup);
    }

    /** The driver sets out. The customer gets a new one-time code; only its hash is stored. */
    @Transactional
    public Map<String, Object> start(String driverId, String returnId) {
        ReturnRequest ret = lockApproved(returnId);
        ReturnPickup pickup = heldBy(driverId, returnId, ReturnPickup.Status.ASSIGNED);
        String code = codeGenerator.next();
        pickup.setStatus(ReturnPickup.Status.ON_THE_WAY);
        pickup.setStartedAt(LocalDateTime.now());
        pickup.setCodeHash(DeliveryService.hash(returnId, code));
        pickup.setCodeAttempts(0);
        Order order = orders.findById(ret.getOrderId()).orElse(null);
        // Saved with this change and sent afterwards; the text is removed from the database once sent
        notifications.queue(CustomerNotification.secret(ret.getOrderId(), ret.getCustomerId(),
                "We are on the way to collect your return",
                "Our delivery partner is on the way to collect " + describe(order, ret) + " (return " + returnId
                        + "). Please have the items packed. When you hand them over, give the driver this code: "
                        + code + ". Never share it before the driver has the items."));
        return details(ret, pickup);
    }

    /**
     * The customer handed the items over and gave the code. Wrong codes are counted even though the
     * request fails, so the code cannot be guessed.
     */
    @Transactional(noRollbackFor = InvalidOrderException.class)
    public Map<String, Object> collect(String driverId, String returnId, String code) {
        ReturnRequest ret = lockApproved(returnId);
        ReturnPickup pickup = heldBy(driverId, returnId, ReturnPickup.Status.ON_THE_WAY);
        if (pickup.getCodeAttempts() >= MAX_CODE_ATTEMPTS) {
            throw new InvalidOrderStateException("Too many wrong codes for return " + returnId + ". Mark the pickup as failed.");
        }
        if (code == null || pickup.getCodeHash() == null || !MessageDigest.isEqual(
                DeliveryService.hash(returnId, code.trim()).getBytes(StandardCharsets.UTF_8),
                pickup.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            pickup.setCodeAttempts(pickup.getCodeAttempts() + 1);
            pickups.save(pickup);
            throw new InvalidOrderException("Wrong pickup code");
        }
        pickup.setStatus(ReturnPickup.Status.COLLECTED);
        pickup.setCollectedAt(LocalDateTime.now());
        pickup.setPayAmount(pay.pickup());
        pickup.setCodeHash(null);
        ret.pickedUp(driverId);
        Order order = orders.findById(ret.getOrderId()).orElse(null);
        tell(ret, "We collected your return " + returnId,
                "Our delivery partner collected " + describe(order, ret) + ". Once the items reach our warehouse and "
                        + "are checked, we refund you and send the credit note, usually within 2 working days.");
        log.info("[PICKUP] {} collected return {}", driverId, returnId);
        return details(ret, pickup);
    }

    /** Before setting out the return simply goes back on the list; at the door, the customer is told too. */
    @Transactional
    public Map<String, Object> fail(String driverId, String returnId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new InvalidOrderException("A reason of 1 to 255 characters is required");
        }
        ReturnRequest ret = lockApproved(returnId);
        ReturnPickup pickup = pickups.findLive(returnId)
                .filter(p -> p.getDeliveryUserId().equals(driverId))
                .orElseThrow(() -> new NotFoundException("Return " + returnId + " not found"));
        boolean wasOnTheWay = pickup.getStatus() == ReturnPickup.Status.ON_THE_WAY;
        pickup.setStatus(ReturnPickup.Status.FAILED);
        pickup.setFailedAt(LocalDateTime.now());
        pickup.setPayAmount(pay.failed(wasOnTheWay));
        pickup.setFailureReason(reason.trim());
        pickup.setCodeHash(null);
        if (wasOnTheWay) {
            tell(ret, "We could not collect your return " + returnId,
                    "Our delivery partner could not collect your return today (" + reason.trim()
                            + "). We will try again; you will get a new code when they are on the way.");
        }
        log.warn("[PICKUP] Attempt {} for return {} failed: {}", pickup.getAttemptNumber(), returnId, reason);
        return details(ret, pickup);
    }

    // ── Admin ──────────────────────────────────────────────────────────────────

    /** Gives the pickup to a driver, taking it from another who has not set out yet. */
    @Transactional
    public Map<String, Object> assign(String returnId, String driverId) {
        AppUser driver = users.findById(driverId == null ? "" : driverId)
                .filter(u -> u.isActive() && u.getRoles().contains(Role.DELIVERY))
                .orElseThrow(() -> new InvalidOrderException(driverId + " is not an active delivery user"));
        ReturnRequest ret = lockApproved(returnId);
        requireSameWarehouse(ret, driver.getUserId(), false);
        pickups.findLive(returnId).ifPresent(current -> {
            if (current.getStatus() == ReturnPickup.Status.ON_THE_WAY) {
                throw new InvalidOrderStateException("Return " + returnId + " is already on its way with "
                        + current.getDeliveryUserId());
            }
            current.setStatus(ReturnPickup.Status.REASSIGNED);
            // Freed first: only one live attempt per return is allowed
            pickups.saveAndFlush(current);
        });
        ReturnPickup pickup = pickups.saveAndFlush(newAttempt(returnId, driver.getUserId()));
        log.info("[PICKUP] Admin assigned return {} to {}", returnId, driverId);
        return details(ret, pickup);
    }

    /** The goods reached the hub some other way: any pickup still open is no longer needed. */
    @Transactional
    public void cancelOpenPickup(String returnId) {
        pickups.findLive(returnId).ifPresent(p -> {
            p.setStatus(ReturnPickup.Status.CANCELLED);
            p.setCodeHash(null);
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private ReturnRequest lockApproved(String returnId) {
        ReturnRequest ret = returns.findById(returnId).orElseThrow(() -> new NotFoundException("Return " + returnId + " not found"));
        // The order's row lock serialises every change to its returns, as in ReturnService
        orderStateService.lock(ret.getOrderId());
        ret = returns.findById(returnId).orElseThrow();
        if (ret.getStatus() != ReturnRequest.Status.APPROVED) {
            throw new InvalidOrderStateException("Return " + returnId + " is " + ret.getStatus() + ", not waiting for a pickup");
        }
        return ret;
    }

    private ReturnPickup newAttempt(String returnId, String driverId) {
        return new ReturnPickup(returnId, driverId, (int) pickups.countByReturnId(returnId) + 1);
    }

    /** The driver's live attempt; another driver's pickup answers as missing. */
    private ReturnPickup heldBy(String driverId, String returnId, ReturnPickup.Status expected) {
        ReturnPickup pickup = pickups.findLive(returnId)
                .filter(p -> p.getDeliveryUserId().equals(driverId))
                .orElseThrow(() -> new NotFoundException("Return " + returnId + " not found"));
        if (pickup.getStatus() != expected) {
            throw new InvalidOrderStateException("Pickup of " + returnId + " is " + pickup.getStatus() + ", not " + expected);
        }
        return pickup;
    }

    private Map<String, Object> details(ReturnRequest ret, ReturnPickup pickup) {
        Order order = orders.findById(ret.getOrderId()).orElse(null);
        ShippingAddress address = order == null ? null : order.getShippingAddress();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("returnId", ret.getReturnId());
        row.put("orderId", ret.getOrderId());
        row.put("returnStatus", ret.getStatus());
        row.put("pickupStatus", pickup.getStatus());
        row.put("attempt", pickup.getAttemptNumber());
        row.put("reason", ret.getReason());
        row.put("recipientName", address != null ? address.getRecipientName() : null);
        row.put("recipientPhone", address != null ? address.getRecipientPhone() : null);
        row.put("address", address);
        row.put("items", ret.getItems().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sku", i.getSku());
            item.put("name", productName(order, i));
            item.put("quantity", i.getQuantity());
            return item;
        }).toList());
        row.put("codeAttemptsLeft", Math.max(0, MAX_CODE_ATTEMPTS - pickup.getCodeAttempts()));
        return row;
    }

    private static String productName(Order order, ReturnItem item) {
        if (order == null) {
            return null;
        }
        return order.getItems().stream().filter(l -> l.getId().equals(item.getOrderItemId()))
                .map(OrderItem::getProductName).findFirst().orElse(null);
    }

    private static String describe(Order order, ReturnRequest ret) {
        return String.join(", ", ret.getItems().stream().map(i -> {
            String name = productName(order, i);
            return i.getQuantity() + " × " + (name != null ? name : i.getSku());
        }).toList());
    }

    private void tell(ReturnRequest ret, String subject, String text) {
        notifications.queue(new CustomerNotification(ret.getOrderId(), ret.getCustomerId(), subject, text));
    }
}
