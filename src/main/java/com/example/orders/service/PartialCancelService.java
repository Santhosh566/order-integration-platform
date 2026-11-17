package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.exception.OrderNotFoundException;
import com.example.orders.model.CreditNote;
import com.example.orders.model.CreditNoteLine;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.PaymentTransaction;
import com.example.orders.model.StockMovement;
import com.example.orders.money.Money;
import com.example.orders.repository.CreditNoteRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cancels some units of an order while it is still in the warehouse.
 *
 * The invoice is a GST document and is never rewritten: it keeps the ordered quantities, and a
 * CANCELLATION credit note takes the cancelled units off it. Each unit is credited at its share
 * of the line, so its part of a coupon discount goes with it. A prepaid order is refunded that
 * amount; a cash-on-delivery order not paid yet just owes less at the door. The units go back
 * on the shelf. Cancelling every unit that is left cancels the whole order instead.
 */
@Service
public class PartialCancelService {

    private static final Logger log = LoggerFactory.getLogger(PartialCancelService.class);

    /** Paid (or COD confirmed), not yet handed to a driver. Earlier statuses are mid-saga. */
    static final Set<OrderStatus> CANCELLABLE_IN_PART =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.READY_FOR_DISPATCH);

    public record CancelLine(String sku, Integer quantity) {}

    public record CancelItems(List<CancelLine> items, String reason) {}

    /** What happened: the order as it is now, and the credit note (null when the whole order was cancelled). */
    public record Result(Order order, CreditNote creditNote, boolean wholeOrderCancelled, BigDecimal refunded) {}

    private final OrderStateService orderStateService;
    private final OrderAccessService orderAccess;
    private final InventoryService inventoryService;
    private final InventoryRepository inventory;
    private final StockLedger stockLedger;
    private final PaymentGatewayService payments;
    private final InvoiceService invoices;
    private final InvoiceNumberService documentNumbers;
    private final CreditNoteRepository creditNotes;
    private final ReturnService returnService;
    private final CustomerNotificationService notifications;
    private final CurrentUser currentUser;

    public PartialCancelService(OrderStateService orderStateService, OrderAccessService orderAccess,
                                InventoryService inventoryService, InventoryRepository inventory,
                                StockLedger stockLedger, PaymentGatewayService payments, InvoiceService invoices,
                                InvoiceNumberService documentNumbers, CreditNoteRepository creditNotes,
                                ReturnService returnService, CustomerNotificationService notifications,
                                CurrentUser currentUser) {
        this.orderStateService = orderStateService;
        this.orderAccess = orderAccess;
        this.inventoryService = inventoryService;
        this.inventory = inventory;
        this.stockLedger = stockLedger;
        this.payments = payments;
        this.invoices = invoices;
        this.documentNumbers = documentNumbers;
        this.creditNotes = creditNotes;
        this.returnService = returnService;
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    /**
     * @throws OrderNotFoundException      when the order does not exist or is not the caller's
     * @throws InvalidOrderException       when the request names unknown items or too many units
     * @throws InvalidOrderStateException  when the order has left the warehouse or is not paid yet
     */
    @Transactional
    public Result cancelItems(String orderId, CancelItems request) {
        if (orderId == null || orderAccess.findVisible(orderId) == null) {
            throw new OrderNotFoundException(orderId);
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new InvalidOrderException("Choose at least one item to cancel");
        }
        String reason = reason(request.reason());

        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (CancelLine line : request.items()) {
            if (line == null || line.sku() == null || line.quantity() == null || line.quantity() < 1) {
                throw new InvalidOrderException("Every cancelled item needs a sku and a quantity of at least 1");
            }
            wanted.merge(line.sku(), line.quantity(), Integer::sum);
        }

        Order order = orderStateService.lock(orderId);
        if (!CANCELLABLE_IN_PART.contains(order.getStatus())) {
            throw new InvalidOrderStateException(order.getStatus().isCancellable()
                    ? "Order " + orderId + " is still being processed. Try again in a minute."
                    : "Order " + orderId + " is " + order.getStatus()
                            + ": items can only be cancelled before it leaves the warehouse");
        }

        // The same SKU can be on several lines; units come off them in order
        Map<OrderItem, Integer> picked = new LinkedHashMap<>();
        wanted.forEach((sku, quantity) -> {
            int left = quantity;
            for (OrderItem line : order.getItems()) {
                if (left == 0 || !line.getSku().equals(sku)) {
                    continue;
                }
                int now = Math.min(line.getActiveQuantity(), left);
                if (now > 0) {
                    picked.put(line, now);
                    left -= now;
                }
            }
            if (left > 0) {
                throw new InvalidOrderException("Only " + (quantity - left) + " of " + sku
                        + " on order " + orderId + " can still be cancelled");
            }
        });

        int unitsLeft = order.getItems().stream().mapToInt(OrderItem::getActiveQuantity).sum()
                - picked.values().stream().mapToInt(Integer::intValue).sum();
        if (unitsLeft == 0) {
            // Nothing would be left to deliver: that is a full cancellation, with its full refund
            Order cancelled = inventoryService.cancel(orderId);
            log.info("[PARTIAL CANCEL] {} cancelled every remaining item of order {}", stockLedger.actor(), orderId);
            return new Result(cancelled, null, true, null);
        }

        // A credit note refers to an invoice, so the order gets its number now if it has none
        invoices.ensureInvoiceNumber(orderId);

        Map<Long, ReturnService.Credited> credited = returnService.credited(order);
        List<CreditNoteLine> lines = new ArrayList<>();
        Map<String, Integer> release = new TreeMap<>();
        picked.forEach((line, units) -> {
            CreditNoteLine credit = ReturnService.creditForUnits(line, units,
                    credited.getOrDefault(line.getId(), ReturnService.Credited.NONE));
            credited.merge(line.getId(), ReturnService.Credited.of(credit), ReturnService.Credited::plus);
            lines.add(credit);
            line.setCancelledQuantity(line.getCancelledQuantity() + units);
            release.merge(line.getSku(), units, Integer::sum);
        });

        // Sorted by SKU, the same lock order as every other stock change
        release.forEach((sku, units) -> inventory.findForUpdate(sku).ifPresent(stock ->
                stockLedger.record(stock, StockMovement.Type.RELEASE, units,
                        -Math.min(units, stock.getReservedQuantity()), orderId, "Cancelled before dispatch")));

        String actor = stockLedger.actor();
        CreditNote note = new CreditNote(documentNumbers.next(InvoiceNumberService.Series.CREDIT_NOTE, LocalDate.now()),
                order, null, CreditNote.Kind.CANCELLATION, lines, reason, actor);
        if (!Money.isPositive(note.getTotal())) {
            throw new InvalidOrderStateException("Nothing is left to credit on these items of order " + orderId);
        }
        order.setCancelledTotal(order.getCancelledTotal().add(note.getTotal()));

        BigDecimal refunded = null;
        if (PaymentGatewayService.isCharged(order)) {
            PaymentTransaction refund = payments.refundPart(order, note.getTotal());
            note.paidBy(refund);
            refunded = note.getTotal();
        }
        CreditNote saved = creditNotes.save(note);

        notifications.queue(new CustomerNotification(orderId, order.getCustomerId(),
                "Items cancelled from order " + orderId,
                "We cancelled " + describe(picked) + " from order " + orderId + ". "
                        + (refunded != null
                            ? Money.format(refunded, order.getCurrency()) + " is being refunded to your original payment method. "
                            : "You now pay " + Money.format(order.amountDue(), order.getCurrency()) + " in cash on delivery. ")
                        + "Credit note: " + saved.getNumber() + ". The rest of your order is on its way as planned."));
        log.info("[PARTIAL CANCEL] {} cancelled {} from order {}: credit note {} for {} {}{}", actor, describe(picked),
                orderId, saved.getNumber(), order.getCurrency(), saved.getTotal(), refunded != null ? " (refunded)" : "");
        return new Result(order, saved, false, refunded);
    }

    private String reason(String given) {
        if (given != null && !given.isBlank()) {
            if (given.trim().length() > 500) {
                throw new InvalidOrderException("reason must be at most 500 characters");
            }
            return given.trim();
        }
        boolean staff = currentUser.get().map(v -> v.isAdmin()).orElse(false);
        return staff ? "Cancelled by staff before dispatch" : "Cancelled by the customer before dispatch";
    }

    private static String describe(Map<OrderItem, Integer> picked) {
        List<String> parts = new ArrayList<>();
        picked.forEach((line, units) -> parts.add(units + " × "
                + (line.getProductName() != null ? line.getProductName() : line.getSku())));
        return String.join(", ", parts);
    }
}
