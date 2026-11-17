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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way stock quantities change. Each change is applied to the product's row and written
 * to stock_movements in the same transaction, so the ledger and the balances always agree.
 *
 * Callers pass a row they hold the lock on (InventoryRepository.findForUpdate).
 */
@Service
public class StockLedger {

    private static final Logger log = LoggerFactory.getLogger(StockLedger.class);

    private final StockMovementRepository movements;
    private final CurrentUser currentUser;
    private final CustomerNotificationService notifications;
    private final String stockAlertEmail;
    private final WishlistService wishlist;

    public StockLedger(StockMovementRepository movements,
                       CurrentUser currentUser,
                       CustomerNotificationService notifications,
                       @Value("${orders.catalog.stock-alert-email:}") String stockAlertEmail,
                       WishlistService wishlist) {
        this.movements = movements;
        this.currentUser = currentUser;
        this.notifications = notifications;
        this.stockAlertEmail = stockAlertEmail;
        this.wishlist = wishlist;
    }

    /**
     * @param availableChange added to available stock (negative takes away)
     * @param reservedChange  added to reserved stock (negative takes away)
     * @throws InvalidOrderStateException when either balance would go below zero
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement record(InventoryItem lockedItem, StockMovement.Type type, int availableChange,
                                int reservedChange, String orderId, String reason) {
        int availableBefore = lockedItem.getAvailableQuantity();
        int available = availableBefore + availableChange;
        int reserved = lockedItem.getReservedQuantity() + reservedChange;
        if (available < 0 || reserved < 0) {
            throw new InvalidOrderStateException("Not enough stock of " + lockedItem.getSku() + ": "
                    + availableBefore + " available, " + lockedItem.getReservedQuantity() + " reserved");
        }
        lockedItem.setAvailableQuantity(available);
        lockedItem.setReservedQuantity(reserved);
        StockMovement movement = movements.save(new StockMovement(
                lockedItem, type, availableChange, reservedChange, orderId, reason, actor()));

        log.info("[STOCK] {} {}: available {} ({}{}), reserved {} ({}{}){}", type, lockedItem.getSku(),
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
