package com.example.orders.service;

import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.InventoryItem;
import com.example.orders.model.StockMovement;
import com.example.orders.repository.StockMovementRepository;
import com.example.orders.security.CurrentUser;
import com.example.orders.security.Viewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way stock quantities change. Stock is kept per warehouse (warehouse_stock); each change is applied to
 * that warehouse's row, to the product's totals over all warehouses (inventory), and written to stock_movements, in
 * one transaction, so the ledger, the warehouses and the totals always agree.
 *
 * Callers pass a product row they hold the lock on (InventoryRepository.findForUpdate); the warehouse's row is
 * locked here, after it. Everything locks products first, then warehouse rows, so two orders cannot deadlock.
 */
@Service
public class StockLedger {

    private static final Logger log = LoggerFactory.getLogger(StockLedger.class);

    private final StockMovementRepository movements;
    private final JdbcTemplate jdbc;
    private final com.example.orders.warehouse.WarehouseService warehouses;
    private final CurrentUser currentUser;
    private final CustomerNotificationService notifications;
    private final String stockAlertEmail;
    private final WishlistService wishlist;

    public StockLedger(StockMovementRepository movements, JdbcTemplate jdbc,
                       com.example.orders.warehouse.WarehouseService warehouses,
                       CurrentUser currentUser,
                       CustomerNotificationService notifications,
                       @Value("${orders.catalog.stock-alert-email:}") String stockAlertEmail,
                       WishlistService wishlist) {
        this.movements = movements;
        this.jdbc = jdbc;
        this.warehouses = warehouses;
        this.currentUser = currentUser;
        this.notifications = notifications;
        this.stockAlertEmail = stockAlertEmail;
        this.wishlist = wishlist;
    }

    /** One warehouse's balances of one product. */
    public record Balance(int available, int reserved) {}

    /**
     * @param warehouseId     the warehouse whose stock changes
     * @param availableChange added to available stock (negative takes away)
     * @param reservedChange  added to reserved stock (negative takes away)
     * @throws InvalidOrderStateException when a balance of the warehouse would go below zero
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement record(InventoryItem lockedItem, long warehouseId, StockMovement.Type type, int availableChange,
                                int reservedChange, String orderId, String reason) {
        String sku = lockedItem.getSku();
        Balance before = lock(warehouseId, sku);
        int available = before.available() + availableChange;
        int reserved = before.reserved() + reservedChange;
        if (available < 0 || reserved < 0) {
            throw new InvalidOrderStateException("Not enough stock of " + sku + " in " + code(warehouseId) + ": "
                    + before.available() + " available, " + before.reserved() + " reserved");
        }
        jdbc.update("update warehouse_stock set available_quantity = ?, reserved_quantity = ? where warehouse_id = ? and sku = ?",
                available, reserved, warehouseId, sku);

        // The product's totals over all warehouses, which the shop reads
        int availableBefore = lockedItem.getAvailableQuantity();
        lockedItem.setAvailableQuantity(Math.max(0, availableBefore + availableChange));
        lockedItem.setReservedQuantity(Math.max(0, lockedItem.getReservedQuantity() + reservedChange));
        StockMovement movement = movements.save(new StockMovement(sku, warehouseId, type, availableChange, reservedChange,
                available, reserved, orderId, reason, actor()));

        log.info("[STOCK] {} {} in {}: available {} ({}{}), reserved {} ({}{}){}", type, sku, code(warehouseId),
                available, availableChange >= 0 ? "+" : "", availableChange,
                reserved, reservedChange >= 0 ? "+" : "", reservedChange,
                orderId != null ? " for order " + orderId : "");

        alertIfLow(lockedItem, availableBefore);
        if (availableBefore <= 0 && available > 0) {
            // Back in stock: customers who asked are emailed once this change commits
            wishlist.productAvailable(lockedItem);
        }
        return movement;
    }

    /** The warehouse an order's stock is in: the one it was reserved from. */
    public long warehouseOf(com.example.orders.model.Order order) {
        return warehouses.of(order);
    }

    /** A warehouse's balances of a product, without locking; zero when it has none. */
    public Balance balance(long warehouseId, String sku) {
        return jdbc.query("select available_quantity, reserved_quantity from warehouse_stock where warehouse_id = ? and sku = ?",
                (rs, n) -> new Balance(rs.getInt(1), rs.getInt(2)), warehouseId, sku).stream().findFirst()
                .orElse(new Balance(0, 0));
    }

    /** The warehouse's row for the product, created empty if missing, locked until the transaction ends. */
    private Balance lock(long warehouseId, String sku) {
        jdbc.update("insert into warehouse_stock (warehouse_id, sku) values (?, ?) on conflict do nothing", warehouseId, sku);
        return jdbc.queryForObject("select available_quantity, reserved_quantity from warehouse_stock "
                + "where warehouse_id = ? and sku = ? for update", (rs, n) -> new Balance(rs.getInt(1), rs.getInt(2)), warehouseId, sku);
    }

    private String code(long warehouseId) {
        return jdbc.queryForList("select code from warehouses where warehouse_id = ?", String.class, warehouseId)
                .stream().findFirst().orElse("warehouse " + warehouseId);
    }

    /** Who changed the stock: the signed-in user, or "system" for the order pipeline. */
    String actor() {
        return currentUser.get().map(Viewer::userId).orElse("system");
    }

    /**
     * Alerts once when available stock falls to the reorder level, not on every sale below it.
     * Queued in the same transaction, so a rolled-back change sends nothing.
     */
    private void alertIfLow(InventoryItem item, int availableBefore) {
        int level = item.getReorderLevel();
        if (level <= 0 || stockAlertEmail.isBlank()) {
            return;
        }
        if (availableBefore > level && item.getAvailableQuantity() <= level) {
            String name = item.getProductName() != null ? item.getProductName() + " (" + item.getSku() + ")" : item.getSku();
            notifications.queue(CustomerNotification.toStaff(stockAlertEmail,
                    "Low stock: " + name,
                    "Available stock of " + name + " is down to " + item.getAvailableQuantity()
                            + " (reorder level " + level + "). " + item.getReservedQuantity()
                            + " more are reserved for orders. Please reorder."));
            log.warn("[STOCK] {} fell to {} (reorder level {}); inventory team alerted",
                    item.getSku(), item.getAvailableQuantity(), level);
        }
    }
}
