package com.example.orders.service;

import com.example.orders.delivery.DeliveryEstimator;
import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderRequest;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.ShippingAddress;
import com.example.orders.model.StockMovement;
import com.example.orders.money.IndianState;
import com.example.orders.money.Money;
import com.example.orders.repository.DeliveryAssignmentRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.security.CurrentUser;
import com.example.orders.security.Viewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Changes a customer (or support, for them) makes to an order after placing it:
 *
 * - A new delivery address, until a driver takes the parcel. The order is already at the warehouse of its
 *   delivery region, so the new address must be in the same region. Before the order is packed the state may
 *   change, and GST is worked out again (CGST + SGST within the seller's state, IGST outside it); the total
 *   stays the same, as prices include GST. Once packed, the tax invoice is issued for a place of supply and
 *   is never rewritten, so the new address must be in the same state.
 * - Another delivery day and time, or standard delivery, by the same rules as checkout.
 * - More items, until the order is packed. They are priced at today's catalogue price, without the order's
 *   coupon, and reserved at once. An order paid online pays for them now (from the wallet first, if the
 *   customer asks, then by card); a cash-on-delivery order owes more at the door.
 *
 * Every change carries the order version the person saw (If-Match), runs under the order's row lock,
 * is written to order_changes, and is emailed to the customer.
 */
@Service
public class OrderChangeService {

    private static final Logger log = LoggerFactory.getLogger(OrderChangeService.class);

    /** In the warehouse, or back at the hub after a failed attempt; a driver taking it ends this (checked apart). */
    static final Set<OrderStatus> DELIVERY_CHANGEABLE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED,
            OrderStatus.READY_FOR_DISPATCH, OrderStatus.DELIVERY_FAILED);

    /** Paid and not packed yet: packing issues the invoice (READY_FOR_DISPATCH). */
    static final Set<OrderStatus> ITEMS_ADDABLE = EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED);

    static final int MAX_UNITS_PER_LINE = 99;
    static final int MAX_PRODUCTS_PER_CHANGE = 20;

    public enum Kind { ADDRESS, DELIVERY_SLOT, ITEMS_ADDED }

    /**
     * One change, as shown in the order's history.
     *
     * @param changedBy who made it; null when a customer reads it and support made it
     * @param amount    ITEMS_ADDED: what the added items cost, GST included
     */
    public record Change(long id, Kind kind, String before, String after, BigDecimal amount, String note,
                         String changedBy, boolean byStaff, LocalDateTime createdAt) {}

    /**
     * @param deliverySlot the delivery to book with the new address: left out keeps the booked one (it must still
     *                     be offered there), both fields null is standard delivery
     */
    public record AddressChange(ShippingAddress address, OrderRequest.DeliverySlot deliverySlot) {}

    public record AddLine(String sku, Integer quantity) {}

    /** @param useWallet pay from the wallet first, the card the rest (orders paid online only) */
    public record AddItems(List<AddLine> items, Boolean useWallet) {}

    /** What was paid for added items: by card, from the wallet, or added to the cash due at the door. */
    public record Payment(BigDecimal byCard, BigDecimal fromWallet, BigDecimal cashOnDelivery) {}

    /** @param changes what was recorded (an address change can also move the delivery day) */
    public record Result(Order order, List<Change> changes, Payment payment) {}

    private final OrderStateService orderStateService;
    private final OrderAccessService orderAccess;
    private final CheckoutService checkout;
    private final CatalogService catalog;
    private final com.example.orders.warehouse.WarehouseAllocator allocator;
    private final DeliveryEstimator estimator;
    private final InventoryRepository inventory;
    private final StockLedger stockLedger;
    private final PaymentGatewayService payments;
    private final WalletService wallets;
    private final DeliveryAssignmentRepository deliveryAssignments;
    private final CustomerNotificationService notifications;
    private final OrderEventBroadcaster broadcaster;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;
    private final BigDecimal maxOrderValue;

    public OrderChangeService(OrderStateService orderStateService, OrderAccessService orderAccess,
                              CheckoutService checkout, CatalogService catalog,
                              com.example.orders.warehouse.WarehouseAllocator allocator,
                              DeliveryEstimator estimator, InventoryRepository inventory, StockLedger stockLedger,
                              PaymentGatewayService payments, WalletService wallets,
                              DeliveryAssignmentRepository deliveryAssignments,
                              CustomerNotificationService notifications, OrderEventBroadcaster broadcaster,
                              CurrentUser currentUser, JdbcTemplate jdbc,
                              @Value("${orders.risk.max-order-value}") BigDecimal maxOrderValue) {
        this.orderStateService = orderStateService;
        this.orderAccess = orderAccess;
        this.checkout = checkout;
        this.catalog = catalog;
        this.allocator = allocator;
        this.estimator = estimator;
        this.inventory = inventory;
        this.stockLedger = stockLedger;
        this.payments = payments;
        this.wallets = wallets;
        this.deliveryAssignments = deliveryAssignments;
        this.notifications = notifications;
        this.broadcaster = broadcaster;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.maxOrderValue = maxOrderValue;
    }

    // ── Delivery address ────────────────────────────────────────────────────

    /**
     * @param expectedVersion the order version the person saw (If-Match), or null
     * @throws InvalidOrderException      when the address is not one we can deliver to, or the slot cannot be kept
     * @throws InvalidOrderStateException when a driver has the parcel, or the address is outside what the order allows
     */
    @Transactional
    public Result changeAddress(String orderId, AddressChange request, Long expectedVersion) {
        requireVisible(orderId);
        if (request == null || request.address() == null) {
            throw new InvalidOrderException("Give the new delivery address");
        }
        ShippingAddress next = new ShippingAddress(request.address());
        OrderValidator.validateAddress(next);
        IndianState nextState = GstService.placeOfSupply(next);
        DeliveryEstimator.Estimate estimate = estimator.estimate(next.getPostalCode(), true);
        if (estimate.outcome() != DeliveryEstimator.Outcome.DELIVERABLE) {
            throw new InvalidOrderException("We do not deliver to PIN " + next.getPostalCode().trim()
                    + ". Check it, or choose another address.");
        }
        if (estimate.deliveryState() != null && estimate.deliveryState() != nextState) {
            throw new InvalidOrderException("PIN " + estimate.pin() + " is in " + estimate.deliveryState().displayName()
                    + ", not " + nextState.displayName() + ". Check the PIN code and the state.");
        }

        Order order = orderStateService.lock(orderId, expectedVersion);
        requireDeliveryChangeable(order, "the delivery address");
        ShippingAddress current = order.getShippingAddress();
        if (current != null && sameAddress(current, next)) {
            throw new InvalidOrderException("That is already the delivery address of this order");
        }

        IndianState currentState = order.getPlaceOfSupply() == null ? null
                : IndianState.find(order.getPlaceOfSupply()).orElse(null);
        if (currentState != null && currentState.region() != nextState.region()) {
            throw new InvalidOrderStateException("Order " + orderId + " is with our " + regionName(currentState.region())
                    + " warehouse, which does not deliver to " + nextState.displayName()
                    + ". To have it delivered there, cancel it and place a new order.");
        }
        boolean stateChanges = currentState != nextState;
        if (stateChanges && order.getInvoiceNumber() != null) {
            throw new InvalidOrderStateException("The tax invoice for order " + orderId + " was issued for delivery in "
                    + (currentState == null ? "another state" : currentState.displayName())
                    + ", so the new address must be in that state too. To have it delivered to "
                    + nextState.displayName() + ", cancel it and place a new order.");
        }

        // The delivery day: the one asked for, or the booked one, which must also be offered at the new address
        LocalDate date = order.getDeliveryDate();
        Order.DeliveryWindow window = order.getDeliveryWindow();
        boolean slotChosen = request.deliverySlot() != null;
        if (slotChosen) {
            date = request.deliverySlot().date();
            window = request.deliverySlot().window();
        }
        try {
            checkout.checkSlot(next, date, window, orderId);
        } catch (InvalidOrderException e) {
            if (slotChosen) {
                throw e;
            }
            throw new InvalidOrderException("Delivery on " + CheckoutService.describeSlot(date, window)
                    + " is not available at the new address. Choose another day, or standard delivery.");
        }

        String actor = actor();
        List<Change> changes = new ArrayList<>();
        String before = current == null ? null : current.singleLine();
        BigDecimal totalBefore = order.getTotal();
        order.setShippingAddress(next);
        String taxNote = null;
        if (stateChanges) {
            // Place of supply moves: each line's GST is split again against the warehouse it ships from; with prices
            // that include GST the total stays
            allocator.shipFrom(order, stockLedger.warehouseOf(order));
            if (order.getTotal().compareTo(totalBefore) != 0) {
                throw new InvalidOrderStateException("Delivering order " + orderId + " to " + nextState.displayName()
                        + " would change its total. Cancel it and place a new order instead.");
            }
            taxNote = "Place of supply is now " + nextState.displayName() + " ("
                    + (order.getIgstTotal().signum() > 0 ? "IGST" : "CGST + SGST") + "); the total is unchanged";
        }
        changes.add(record(order, Kind.ADDRESS, before, next.singleLine(), null, taxNote, actor));

        String slotBefore = CheckoutService.describeSlot(order.getDeliveryDate(), order.getDeliveryWindow());
        String slotAfter = CheckoutService.describeSlot(date, window);
        if (!slotBefore.equals(slotAfter)) {
            order.setDeliveryDate(date);
            order.setDeliveryWindow(window);
            changes.add(record(order, Kind.DELIVERY_SLOT, slotBefore, slotAfter, null, null, actor));
        }

        notifications.queue(new CustomerNotification(orderId, order.getCustomerId(),
                "New delivery address for order " + orderId,
                "Order " + orderId + " will now be delivered to: " + next.singleLine() + ". "
                        + (slotBefore.equals(slotAfter) ? "" : "Delivery: " + slotAfter + ". ")
                        + (taxNote != null ? taxNote + ". " : "")
                        + "If you did not make this change, contact us at once."));
        log.info("[ORDER CHANGE] {} moved the delivery of order {} to PIN {}{}", actor, orderId,
                next.getPostalCode(), stateChanges ? " (" + nextState.displayName() + ", GST worked out again)" : "");
        broadcaster.broadcast("ORDER_UPDATED", order);
        return new Result(order, changes, null);
    }

    // ── Delivery day and time ───────────────────────────────────────────────

    /**
     * @param slot the day and window to book; both null for standard delivery
     */
    @Transactional
    public Result changeSlot(String orderId, OrderRequest.DeliverySlot slot, Long expectedVersion) {
        requireVisible(orderId);
        if (slot == null) {
            throw new InvalidOrderException("Choose a delivery day and time, or standard delivery");
        }
        Order order = orderStateService.lock(orderId, expectedVersion);
        requireDeliveryChangeable(order, "the delivery day");
        String before = CheckoutService.describeSlot(order.getDeliveryDate(), order.getDeliveryWindow());
        String after = CheckoutService.describeSlot(slot.date(), slot.window());
        if (Objects.equals(order.getDeliveryDate(), slot.date()) && Objects.equals(order.getDeliveryWindow(), slot.window())) {
            throw new InvalidOrderException(slot.date() == null
                    ? "This order is already on standard delivery"
                    : "That is already the delivery booked for this order");
        }
        checkout.checkSlot(order.getShippingAddress(), slot.date(), slot.window(), orderId);
        order.setDeliveryDate(slot.date());
        order.setDeliveryWindow(slot.window());

        String actor = actor();
        Change change = record(order, Kind.DELIVERY_SLOT, before, after, null, null, actor);
        notifications.queue(new CustomerNotification(orderId, order.getCustomerId(),
                "New delivery day for order " + orderId,
                "Order " + orderId + " is now booked for: " + after + " (was: " + before + ")."
                        + (slot.date() != null ? " Someone needs to be at the address to take the parcel." : "")));
        log.info("[ORDER CHANGE] {} moved the delivery of order {} from {} to {}", actor, orderId, before, after);
        broadcaster.broadcast("ORDER_UPDATED", order);
        return new Result(order, List.of(change), null);
    }

    // ── Adding items ────────────────────────────────────────────────────────

    /**
     * @throws InvalidOrderException      when a product is unknown, not sold, short of stock or over its limit,
     *                                    or the payment cannot cover it
     * @throws InvalidOrderStateException when the order is packed or no longer live
     */
    @Transactional
    public Result addItems(String orderId, AddItems request, Long expectedVersion) {
        requireVisible(orderId);
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new InvalidOrderException("Choose at least one item to add");
        }
        // Sorted by SKU: the same lock order as every other stock change
        Map<String, Integer> wanted = new TreeMap<>();
        for (AddLine line : request.items()) {
            if (line == null || line.sku() == null || line.sku().isBlank() || line.quantity() == null
                    || line.quantity() < 1 || line.quantity() > MAX_UNITS_PER_LINE) {
                throw new InvalidOrderException("Every item needs a sku and a quantity from 1 to " + MAX_UNITS_PER_LINE);
            }
            wanted.merge(line.sku().strip(), line.quantity(), Integer::sum);
        }
        if (wanted.size() > MAX_PRODUCTS_PER_CHANGE) {
            throw new InvalidOrderException("Add at most " + MAX_PRODUCTS_PER_CHANGE + " products at a time");
        }

        Order order = orderStateService.lock(orderId, expectedVersion);
        requireItemsAddable(order);
        boolean staff = isStaff();
        boolean useWallet = Boolean.TRUE.equals(request.useWallet());
        if (useWallet && order.isCashOnDelivery()) {
            throw new InvalidOrderException("Wallet balance can be used with online payment only, not with cash on delivery");
        }

        // Stock rows locked first, so the prices and quantities read below are current. Added items come in the same
        // parcel, so they must be in the warehouse the order ships from.
        long warehouse = stockLedger.warehouseOf(order);
        Map<String, InventoryItem> stock = new TreeMap<>();
        wanted.forEach((sku, units) -> {
            InventoryItem item = inventory.findForUpdate(sku)
                    .orElseThrow(() -> new InvalidOrderException("Unknown product: " + sku));
            if (!item.isActive() || item.getUnitPrice() == null) {
                throw new InvalidOrderException(item.getProductName() + " is no longer sold");
            }
            int there = stockLedger.balance(warehouse, sku).available();
            if (there < units) {
                throw new InvalidOrderException(there <= 0
                        ? item.getProductName() + " is out of stock in the warehouse your order ships from"
                        : "Only " + there + " of " + item.getProductName() + " left in the warehouse your order ships from");
            }
            Integer max = item.getMaxPerOrder();
            if (!staff && max != null) {
                int already = order.getItems().stream().filter(i -> i.getSku().equals(sku))
                        .mapToInt(OrderItem::getActiveQuantity).sum();
                if (already + units > max) {
                    throw new InvalidOrderException("At most " + max + " of " + item.getProductName() + " per order"
                            + (already > 0 ? "; this order already has " + already : ""));
                }
            }
            stock.put(sku, item);
        });

        // Priced as a new order would be today, in the order's place of supply, without its coupon
        Order quote = new Order();
        quote.setOrderId(orderId);
        quote.setCustomerId(order.getCustomerId());
        quote.setShippingAddress(new ShippingAddress(order.getShippingAddress()));
        List<OrderItem> added = new ArrayList<>();
        wanted.forEach((sku, units) -> added.add(new OrderItem(sku, units, Money.ZERO)));
        quote.setItems(added);
        // Same parcel, same warehouse: taxed from its registration
        quote.setWarehouseId(warehouse);
        catalog.price(quote, false);
        BigDecimal extra = added.stream().map(OrderItem::getLineTotal).reduce(Money.ZERO, BigDecimal::add);
        if (!Money.isPositive(extra)) {
            throw new InvalidOrderException("These items cost nothing, so there is nothing to add");
        }
        if (order.getTotal().add(extra).compareTo(maxOrderValue) > 0) {
            throw new InvalidOrderException("With these items the order would be over "
                    + Money.format(maxOrderValue, order.getCurrency()) + ". Place a new order for them instead.");
        }

        // How it is paid, checked before anything changes
        BigDecimal fromWallet = Money.ZERO;
        BigDecimal byCard = Money.ZERO;
        BigDecimal cash = Money.ZERO;
        if (order.isCashOnDelivery()) {
            CheckoutService.CashOnDelivery cod = checkout.cashOnDelivery(order.getCustomerId(), order.amountDue().add(extra));
            if (!cod.available()) {
                throw new InvalidOrderException(cod.reason());
            }
            cash = extra;
        } else {
            if (useWallet) {
                BigDecimal available = wallets.available(order.getCustomerId());
                if (available.signum() == 0) {
                    throw new InvalidOrderException("Your wallet is empty. Pay by card, or add a gift card first.");
                }
                fromWallet = Money.of(available.min(extra));
            }
            byCard = extra.subtract(fromWallet);
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = actor();
        wanted.forEach((sku, units) -> stockLedger.record(stock.get(sku), warehouse, StockMovement.Type.RESERVE, -units, units,
                orderId, "Added to the order"));
        for (OrderItem line : added) {
            line.setAddedAt(now);
            order.getItems().add(line);
        }
        order.recalculateTotals();

        if (fromWallet.signum() > 0) {
            payments.chargeExtraFromWallet(order, fromWallet, "Items added to order " + orderId, actor);
        }
        // The card last: everything that can refuse the change has passed
        if (byCard.signum() > 0) {
            payments.chargeExtra(order, byCard);
        }

        String what = describe(added);
        String paidHow = paidHow(order, byCard, fromWallet, cash);
        Change change = record(order, Kind.ITEMS_ADDED, null, what, extra, paidHow, actor);
        notifications.queue(new CustomerNotification(orderId, order.getCustomerId(),
                "Items added to order " + orderId,
                "We added " + what + " to order " + orderId + " for " + Money.format(extra, order.getCurrency())
                        + ". " + paidHow + ". The order total is now " + Money.format(order.getTotal(), order.getCurrency())
                        + ", and it all comes in one delivery."));
        log.info("[ORDER CHANGE] {} added {} to order {} for {} {} ({})", actor, what, orderId, order.getCurrency(),
                extra, paidHow);
        broadcaster.broadcast("ORDER_UPDATED", order);
        return new Result(order, List.of(change), new Payment(byCard, fromWallet, cash));
    }

    // ── History ─────────────────────────────────────────────────────────────

    /** Every change to the order, oldest first. A customer does not see which staff member made one. */
    @Transactional(readOnly = true)
    public List<Change> changes(String orderId) {
        requireVisible(orderId);
        boolean staff = isStaff();
        return jdbc.query("select * from order_changes where order_id = ? order by created_at, change_id",
                        CHANGE, orderId).stream()
                .map(c -> staff || !c.byStaff() ? c : new Change(c.id(), c.kind(), c.before(), c.after(), c.amount(),
                        c.note(), null, true, c.createdAt()))
                .toList();
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    private void requireVisible(String orderId) {
        if (orderId == null || orderAccess.findVisible(orderId) == null) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private void requireDeliveryChangeable(Order order, String what) {
        OrderStatus status = order.getStatus();
        if (!DELIVERY_CHANGEABLE.contains(status)) {
            throw new InvalidOrderStateException(status == OrderStatus.PENDING || status == OrderStatus.INVENTORY_RESERVED
                    ? "Order " + order.getOrderId() + " is still being processed. Try again in a minute."
                    : "Order " + order.getOrderId() + " is " + status + ": " + what
                            + " can only be changed before a driver takes the parcel");
        }
        if (deliveryAssignments.findLive(order.getOrderId()).isPresent()) {
            throw new InvalidOrderStateException("A driver has already taken order " + order.getOrderId()
                    + ", so " + what + " can no longer be changed");
        }
    }

    private void requireItemsAddable(Order order) {
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.PENDING || status == OrderStatus.INVENTORY_RESERVED) {
            throw new InvalidOrderStateException("Order " + order.getOrderId()
                    + " is still being processed. Try again in a minute.");
        }
        if (ITEMS_ADDABLE.contains(status) && order.getInvoiceNumber() != null) {
            // An earlier partial cancellation issued the invoice, and an invoice is never rewritten
            throw new InvalidOrderStateException("The tax invoice for order " + order.getOrderId()
                    + " has already been issued, so nothing more can be added to it. Place a new order for these items.");
        }
        if (!ITEMS_ADDABLE.contains(status)) {
            throw new InvalidOrderStateException(status.isRevenue()
                    ? "Order " + order.getOrderId() + " is already packed, so nothing more can go in it. "
                            + "Place a new order for these items."
                    : "Order " + order.getOrderId() + " is " + status + ": items can only be added before it is packed");
        }
    }

    private boolean isStaff() {
        return currentUser.get().map(Viewer::isAdmin).orElse(false);
    }

    private String actor() {
        return currentUser.get().map(Viewer::userId).orElse("system");
    }

    private Change record(Order order, Kind kind, String before, String after, BigDecimal amount, String note,
                          String actor) {
        boolean staff = isStaff();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("insert into order_changes "
                    + "(order_id, kind, before_value, after_value, amount, note, changed_by, by_staff) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?)", new String[] {"change_id", "created_at"});
            ps.setString(1, order.getOrderId());
            ps.setString(2, kind.name());
            ps.setString(3, clip(before));
            ps.setString(4, clip(after));
            ps.setBigDecimal(5, amount);
            ps.setString(6, clip(note));
            ps.setString(7, actor);
            ps.setBoolean(8, staff);
            return ps;
        }, keys);
        Map<String, Object> row = keys.getKeys();
        long id = ((Number) Objects.requireNonNull(row).get("change_id")).longValue();
        LocalDateTime at = ((Timestamp) row.get("created_at")).toLocalDateTime();
        return new Change(id, kind, before, after, amount, note, actor, staff, at);
    }

    private static String clip(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 999) + "…";
    }

    static boolean sameAddress(ShippingAddress a, ShippingAddress b) {
        return Objects.equals(norm(a.singleLine()), norm(b.singleLine()))
                && Objects.equals(norm(a.getRecipientPhone()), norm(b.getRecipientPhone()));
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String regionName(IndianState.Region region) {
        return switch (region) {
            case NORTH -> "North India";
            case SOUTH -> "South India";
            case EAST -> "East India";
            case WEST -> "West India";
        };
    }

    private static String describe(List<OrderItem> lines) {
        List<String> parts = new ArrayList<>();
        for (OrderItem line : lines) {
            parts.add(line.getQuantity() + " × " + (line.getProductName() != null ? line.getProductName() : line.getSku()));
        }
        return String.join(", ", parts);
    }

    private static String paidHow(Order order, BigDecimal byCard, BigDecimal fromWallet, BigDecimal cash) {
        String currency = order.getCurrency();
        if (cash.signum() > 0) {
            return "You now pay " + Money.format(order.amountDue(), currency) + " in cash on delivery";
        }
        if (fromWallet.signum() > 0 && byCard.signum() > 0) {
            return Money.format(fromWallet, currency) + " was paid from your wallet and "
                    + Money.format(byCard, currency) + " charged to your card";
        }
        if (fromWallet.signum() > 0) {
            return Money.format(fromWallet, currency) + " was paid from your wallet";
        }
        return Money.format(byCard, currency) + " was charged to your card";
    }

    private static final RowMapper<Change> CHANGE = (rs, n) -> new Change(rs.getLong("change_id"),
            Kind.valueOf(rs.getString("kind")), rs.getString("before_value"), rs.getString("after_value"),
            rs.getBigDecimal("amount"), rs.getString("note"), rs.getString("changed_by"), rs.getBoolean("by_staff"),
            rs.getTimestamp("created_at").toLocalDateTime());
}
