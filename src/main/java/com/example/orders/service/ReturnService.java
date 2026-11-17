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
import com.example.orders.model.ReturnItem;
import com.example.orders.model.ReturnRequest;
import com.example.orders.model.StockMovement;
import com.example.orders.money.Money;
import com.example.orders.repository.CreditNoteRepository;
import com.example.orders.repository.InventoryRepository;
import com.example.orders.repository.ReturnRequestRepository;
import com.example.orders.security.CurrentUser;
import com.example.orders.security.Viewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer returns and partial refunds.
 *
 * Every refund here is backed by a credit note whose lines say how much taxable value and tax of
 * each order line is given back. Amounts are worked out from the order line's snapshot and what
 * earlier credit notes already gave back, so the last units of a line get exactly what is left and
 * rounding can never refund more than was charged, or more tax than was collected.
 *
 * All changes lock the order row first, so two returns or refunds on one order take turns.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    public record ReturnLine(String sku, Integer quantity) {}

    public record NewReturn(String orderId, String reason, List<ReturnLine> items) {}

    public record Decision(String note) {}

    public record Inspection(Map<String, ReturnItem.Condition> conditions) {}

    public record GoodwillRefund(BigDecimal amount, String reason) {}

    private final OrderStateService orderStateService;
    private final OrderAccessService orderAccess;
    private final ReturnRequestRepository returns;
    private final CreditNoteRepository creditNotes;
    private final InventoryRepository inventory;
    private final StockLedger stockLedger;
    private final PaymentGatewayService payments;
    private final InvoiceNumberService documentNumbers;
    private final CustomerNotificationService notifications;
    private final CurrentUser currentUser;
    private final int windowDays;
    private final ReturnPickupService returnPickups;

    public ReturnService(OrderStateService orderStateService, OrderAccessService orderAccess,
                         ReturnRequestRepository returns, CreditNoteRepository creditNotes,
                         InventoryRepository inventory, StockLedger stockLedger, PaymentGatewayService payments,
                         InvoiceNumberService documentNumbers, CustomerNotificationService notifications,
                         CurrentUser currentUser, ReturnPickupService returnPickups,
                         @Value("${orders.returns.window-days}") int windowDays) {
        this.returnPickups = returnPickups;
        this.orderStateService = orderStateService;
        this.orderAccess = orderAccess;
        this.returns = returns;
        this.creditNotes = creditNotes;
        this.inventory = inventory;
        this.stockLedger = stockLedger;
        this.payments = payments;
        this.documentNumbers = documentNumbers;
        this.notifications = notifications;
        this.currentUser = currentUser;
        this.windowDays = windowDays;
    }

    // ── Customer ───────────────────────────────────────────────────────────────

    /**
     * A customer asks to send back units of a delivered order, within the return window.
     */
    @Transactional
    public ReturnRequest request(NewReturn request) {
        if (request.orderId() == null || orderAccess.findVisible(request.orderId()) == null) {
            throw new OrderNotFoundException(request.orderId());
        }
        String reason = requireText(request.reason(), "reason");
        if (request.items() == null || request.items().isEmpty()) {
            throw new InvalidOrderException("Choose at least one item to return");
        }
        Order order = orderStateService.lock(request.orderId());
        if (order.getStatus() != OrderStatus.DELIVERED || order.getDeliveredAt() == null) {
            throw new InvalidOrderStateException("Only a delivered order can be returned; order "
                    + order.getOrderId() + " is " + order.getStatus());
        }
        LocalDateTime closes = order.getDeliveredAt().plusDays(windowDays);
        if (LocalDateTime.now().isAfter(closes)) {
            throw new InvalidOrderStateException("The " + windowDays + "-day return window for order "
                    + order.getOrderId() + " closed on " + closes.toLocalDate());
        }

        // Units of each line already in a return that was not rejected
        Map<Long, Integer> taken = new HashMap<>();
        for (ReturnRequest earlier : returns.findByOrderIdOrderByRequestedAt(order.getOrderId())) {
            if (earlier.isLive()) {
                earlier.getItems().forEach(i -> taken.merge(i.getOrderItemId(), i.getQuantity(), Integer::sum));
            }
        }

        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (ReturnLine line : request.items()) {
            if (line == null || line.sku() == null || line.quantity() == null || line.quantity() < 1) {
                throw new InvalidOrderException("Every returned item needs a sku and a quantity of at least 1");
            }
            wanted.merge(line.sku(), line.quantity(), Integer::sum);
        }

        // The same SKU can be on several lines; units are taken from them in order
        List<ReturnItem> items = new ArrayList<>();
        wanted.forEach((sku, quantity) -> {
            int left = quantity;
            for (OrderItem line : order.getItems()) {
                if (left == 0 || !line.getSku().equals(sku)) {
                    continue;
                }
                int free = line.getActiveQuantity() - taken.getOrDefault(line.getId(), 0);
                int now = Math.min(free, left);
                if (now > 0) {
                    items.add(new ReturnItem(line.getId(), sku, now));
                    left -= now;
                }
            }
            if (left > 0) {
                throw new InvalidOrderException("Only " + (quantity - left) + " of " + sku
                        + " on order " + order.getOrderId() + " can still be returned");
            }
        });

        ReturnRequest saved = returns.save(new ReturnRequest(order.getOrderId(), order.getCustomerId(), reason, items));
        tell(order, "We received your return request for order " + order.getOrderId(),
                "We received your request to return " + describe(items) + " from order " + order.getOrderId()
                        + " (" + saved.getReturnId() + "). We will let you know when it is approved.");
        log.info("[RETURNS] {} requested {} for order {}", saved.getCustomerId(), saved.getReturnId(), order.getOrderId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ReturnRequest> visibleReturns() {
        Viewer viewer = currentUser.require();
        return viewer.isAdmin() ? returns.findAllByOrderByRequestedAtDesc()
                : returns.findByCustomerIdOrderByRequestedAtDesc(viewer.userId());
    }

    @Transactional(readOnly = true)
    public ReturnRequest visibleReturn(String returnId) {
        ReturnRequest found = returns.findById(returnId).orElse(null);
        if (found == null || orderAccess.findVisible(found.getOrderId()) == null) {
            throw new OrderNotFoundException(returnId);
        }
        return found;
    }

    @Transactional(readOnly = true)
    public List<CreditNote> creditNotes(String orderId) {
        if (orderAccess.findVisible(orderId) == null) {
            throw new OrderNotFoundException(orderId);
        }
        return creditNotes.findByOrderIdOrderByIssuedAt(orderId);
    }

    // ── Admin ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReturnRequest> byStatus(ReturnRequest.Status status) {
        return status == null ? returns.findAllByOrderByRequestedAtDesc() : returns.findByStatusOrderByRequestedAt(status);
    }

    @Transactional
    public ReturnRequest approve(String returnId, Decision decision) {
        ReturnRequest ret = lockReturn(returnId);
        ret.approve(stockLedger.actor(), note(decision));
        Order order = orderStateService.lock(ret.getOrderId());
        tell(order, "Your return " + ret.getReturnId() + " was approved",
                "Your return of " + describe(ret.getItems()) + " from order " + order.getOrderId()
                        + " was approved. Our delivery partner will collect it from your address; please pack the "
                        + "items with their original accessories. You get a one-time code by email when they are on "
                        + "the way. We refund you once the goods reach our warehouse and are checked.");
        return ret;
    }

    @Transactional
    public ReturnRequest reject(String returnId, Decision decision) {
        String note = note(decision);
        if (note == null) {
            throw new InvalidOrderException("Tell the customer why: a note is required to reject a return");
        }
        ReturnRequest ret = lockReturn(returnId);
        ret.reject(stockLedger.actor(), note);
        Order order = orderStateService.lock(ret.getOrderId());
        tell(order, "Your return " + ret.getReturnId() + " was not approved",
                "We could not accept your return of " + describe(ret.getItems()) + " from order "
                        + order.getOrderId() + ": " + note);
        return ret;
    }

    /**
     * The goods arrived and were inspected: sellable units go back on the shelf, and the customer is
     * refunded for every unit returned, with a credit note.
     */
    @Transactional
    public CreditNote receive(String returnId, Inspection inspection) {
        ReturnRequest ret = lockReturn(returnId);
        Order order = orderStateService.lock(ret.getOrderId());
        String actor = stockLedger.actor();
        ret.received(actor);
        // Arrived (by pickup or otherwise): a driver still holding the pickup no longer needs to go
        returnPickups.cancelOpenPickup(returnId);

        Map<String, ReturnItem.Condition> conditions = inspection == null || inspection.conditions() == null
                ? Map.of() : inspection.conditions();
        Map<Long, Credited> credited = credited(order);
        List<CreditNoteLine> lines = new ArrayList<>();
        for (ReturnItem item : ret.getItems()) {
            ReturnItem.Condition condition = conditions.getOrDefault(item.getSku(), ReturnItem.Condition.SELLABLE);
            item.inspected(condition);
            if (condition == ReturnItem.Condition.SELLABLE) {
                inventory.findForUpdate(item.getSku()).ifPresent(stock -> stockLedger.record(stock,
                        StockMovement.Type.RETURN, item.getQuantity(), 0, order.getOrderId(), "Customer return " + returnId));
            }
            OrderItem line = line(order, item.getOrderItemId());
            CreditNoteLine credit = creditForUnits(line, item.getQuantity(), credited.getOrDefault(line.getId(), Credited.NONE));
            credited.merge(line.getId(), Credited.of(credit), Credited::plus);
            lines.add(credit);
        }

        CreditNote note = issue(order, ret.getReturnId(), CreditNote.Kind.RETURN, lines, ret.getReason(), actor);
        order.setReturnStatus(allUnitsReturned(order, credited) ? Order.ReturnStatus.FULL : Order.ReturnStatus.PARTIAL);
        tell(order, "Refund for your return " + ret.getReturnId(),
                "We received your return of " + describe(ret.getItems()) + " and refunded "
                        + Money.format(note.getTotal(), note.getCurrency()) + refundRoute(order)
                        + "Credit note: " + note.getNumber() + ".");
        log.info("[RETURNS] {} received {} for order {}: refunded {} {} (credit note {})", actor, returnId,
                order.getOrderId(), note.getCurrency(), note.getTotal(), note.getNumber());
        return note;
    }

    /**
     * Money back without a return, such as a discount for a scratched item. The amount includes
     * GST and is spread over the lines by what is left of each, so the tax given back matches.
     */
    @Transactional
    public CreditNote goodwill(String orderId, GoodwillRefund request) {
        String reason = requireText(request.reason(), "reason");
        Order order = orderStateService.lock(orderId);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new InvalidOrderStateException("A goodwill refund is for a delivered order; " + orderId + " is " + order.getStatus());
        }
        BigDecimal amount = request.amount() == null ? null : Money.of(request.amount());
        if (!Money.isPositive(amount) || request.amount().stripTrailingZeros().scale() > 2) {
            throw new InvalidOrderException("amount must be more than 0, with at most 2 decimals");
        }
        if (amount.compareTo(order.refundableAmount()) > 0) {
            throw new InvalidOrderStateException("Only " + Money.format(order.refundableAmount(), order.getCurrency())
                    + " of order " + orderId + " can still be refunded");
        }

        Map<Long, Credited> credited = credited(order);
        List<OrderItem> open = order.getItems().stream()
                .filter(line -> remaining(line, credited.getOrDefault(line.getId(), Credited.NONE)).total().signum() > 0)
                .sorted(Comparator.comparing((OrderItem line) ->
                        remaining(line, credited.getOrDefault(line.getId(), Credited.NONE)).total()).reversed())
                .toList();
        BigDecimal leftTotal = open.stream()
                .map(line -> remaining(line, credited.getOrDefault(line.getId(), Credited.NONE)).total())
                .reduce(Money.ZERO, BigDecimal::add);

        List<CreditNoteLine> lines = new ArrayList<>();
        BigDecimal allocated = Money.ZERO;
        for (int i = 0; i < open.size(); i++) {
            OrderItem line = open.get(i);
            Credited left = remaining(line, credited.getOrDefault(line.getId(), Credited.NONE));
            // The largest line comes first; the last one takes the rounding remainder
            BigDecimal share = i == open.size() - 1 ? amount.subtract(allocated)
                    : Money.of(amount.multiply(left.total()).divide(leftTotal, MathContext.DECIMAL64));
            if (share.compareTo(left.total()) > 0) {
                share = left.total();
            }
            allocated = allocated.add(share);
            lines.add(splitByTax(line.getId(), share, left));
        }
        if (allocated.compareTo(amount) != 0) {
            throw new InvalidOrderStateException("Could not spread " + amount + " over the order's lines");
        }

        CreditNote note = issue(order, null, CreditNote.Kind.GOODWILL, lines, reason, stockLedger.actor());
        tell(order, "We refunded part of order " + orderId,
                "We refunded " + Money.format(note.getTotal(), note.getCurrency()) + " for order " + orderId + ": "
                        + reason + ". Credit note: " + note.getNumber() + ".");
        return note;
    }

    /** Where the money goes back: the card or UPI it came from, or, for cash paid at the door, a bank transfer. */
    private static String refundRoute(Order order) {
        return order.isCashOnDelivery()
                ? ". You paid in cash on delivery, so it comes by bank transfer or UPI: reply to this email with your "
                        + "account details if we do not have them yet. "
                : " to your original payment method. ";
    }

    // ── Amounts ────────────────────────────────────────────────────────────────

    /** What credit notes already gave back on one order line. */
    record Credited(int quantity, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
        static final Credited NONE = new Credited(0, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

        static Credited of(CreditNoteLine line) {
            return new Credited(line.getQuantity(), line.getTaxableValue(), line.getCgstAmount(),
                    line.getSgstAmount(), line.getIgstAmount());
        }

        Credited plus(Credited other) {
            return new Credited(quantity + other.quantity, taxable.add(other.taxable), cgst.add(other.cgst),
                    sgst.add(other.sgst), igst.add(other.igst));
        }

        BigDecimal total() {
            return taxable.add(cgst).add(sgst).add(igst);
        }
    }

    Map<Long, Credited> credited(Order order) {
        Map<Long, Credited> sums = new HashMap<>();
        for (CreditNote note : creditNotes.findByOrderIdOrderByIssuedAt(order.getOrderId())) {
            for (CreditNoteLine line : note.getLines()) {
                sums.merge(line.getOrderItemId(), Credited.of(line), Credited::plus);
            }
        }
        return sums;
    }

    private static Credited remaining(OrderItem line, Credited credited) {
        return new Credited(line.getQuantity() - credited.quantity(),
                line.getTaxableValue().subtract(credited.taxable()), line.getCgstAmount().subtract(credited.cgst()),
                line.getSgstAmount().subtract(credited.sgst()), line.getIgstAmount().subtract(credited.igst()));
    }

    /**
     * Returned units get their share of the line; the last units of a line get exactly what is left.
     */
    static CreditNoteLine creditForUnits(OrderItem line, int units, Credited credited) {
        Credited left = remaining(line, credited);
        if (units >= left.quantity()) {
            return new CreditNoteLine(line.getId(), units, left.taxable(), left.cgst(), left.sgst(), left.igst());
        }
        BigDecimal taxable = share(line.getTaxableValue(), units, line.getQuantity()).min(left.taxable());
        BigDecimal half = share(line.getCgstAmount(), units, line.getQuantity()).min(left.cgst()).min(left.sgst());
        BigDecimal igst = share(line.getIgstAmount(), units, line.getQuantity()).min(left.igst());
        return new CreditNoteLine(line.getId(), units, taxable, half, half, igst);
    }

    /** Splits a tax-inclusive amount in the proportions of what is left on the line. */
    private static CreditNoteLine splitByTax(Long orderItemId, BigDecimal amount, Credited left) {
        BigDecimal whole = left.total();
        BigDecimal half = Money.of(amount.multiply(left.cgst()).divide(whole, MathContext.DECIMAL64));
        BigDecimal igst = Money.of(amount.multiply(left.igst()).divide(whole, MathContext.DECIMAL64));
        BigDecimal taxable = amount.subtract(half).subtract(half).subtract(igst);
        return new CreditNoteLine(orderItemId, 0, taxable, half, half, igst);
    }

    private static BigDecimal share(BigDecimal amount, int units, int of) {
        return Money.of(amount.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(of), MathContext.DECIMAL64));
    }

    private static boolean allUnitsReturned(Order order, Map<Long, Credited> credited) {
        return order.getItems().stream()
                .allMatch(line -> credited.getOrDefault(line.getId(), Credited.NONE).quantity() >= line.getQuantity());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private CreditNote issue(Order order, String returnId, CreditNote.Kind kind, List<CreditNoteLine> lines,
                             String reason, String actor) {
        CreditNote note = new CreditNote(documentNumbers.next(InvoiceNumberService.Series.CREDIT_NOTE, LocalDate.now()),
                order, returnId, kind, lines, reason, actor);
        if (!Money.isPositive(note.getTotal())) {
            throw new InvalidOrderStateException("Nothing is left to refund on order " + order.getOrderId());
        }
        PaymentTransaction refund = payments.refundPart(order, note.getTotal());
        note.paidBy(refund);
        return creditNotes.save(note);
    }

    private ReturnRequest lockReturn(String returnId) {
        ReturnRequest ret = returns.findById(returnId).orElseThrow(() -> new OrderNotFoundException(returnId));
        // The order's row lock serialises every change to its returns
        orderStateService.lock(ret.getOrderId());
        return returns.findById(returnId).orElseThrow(() -> new OrderNotFoundException(returnId));
    }

    private static OrderItem line(Order order, Long orderItemId) {
        return order.getItems().stream().filter(i -> i.getId().equals(orderItemId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Order " + order.getOrderId() + " has no line " + orderItemId));
    }

    private void tell(Order order, String subject, String text) {
        notifications.queue(new CustomerNotification(order.getOrderId(), order.getCustomerId(), subject, text));
    }

    private static String describe(List<ReturnItem> items) {
        return String.join(", ", items.stream().map(i -> i.getQuantity() + " × " + i.getSku()).toList());
    }

    private static String note(Decision decision) {
        return decision == null || decision.note() == null || decision.note().isBlank() ? null : decision.note().trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidOrderException(field + " is required");
        }
        if (value.trim().length() > 500) {
            throw new InvalidOrderException(field + " must be at most 500 characters");
        }
        return value.trim();
    }
}
