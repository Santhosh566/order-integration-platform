package com.example.orders.model;

import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer order as received by the order API and stored in the orders table.
 * Enhanced in Phase 2 with logistics fulfillment (Carrier, Tracking #) and risk evaluation (Fraud Score).
 */
@Entity
@Table(name = "orders")
@SQLRestriction("deleted_at is null")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Order {

    @Id
    @Column(name = "order_id")
    private String orderId;

    /**
     * Raised by every change to the row. A person's action carries the version they saw (If-Match),
     * and one made on an older copy is refused instead of overwriting what changed since.
     * Empty until the order is first stored, which is how a new order is told from a stored one.
     */
    @Version
    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long version;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "order_type")
    private String orderType;

    // ── Money: what the customer pays, the GST in it, and in which currency ──────
    // ISO 4217 code; orders from before GST were in USD
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    // Sum of the lines' taxable values
    @Column(nullable = false)
    private BigDecimal subtotal = Money.ZERO;

    @Column(name = "cgst_total", nullable = false)
    private BigDecimal cgstTotal = Money.ZERO;

    @Column(name = "sgst_total", nullable = false)
    private BigDecimal sgstTotal = Money.ZERO;

    @Column(name = "igst_total", nullable = false)
    private BigDecimal igstTotal = Money.ZERO;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal = Money.ZERO;

    // Grand total: subtotal plus tax, the amount charged
    @Column(nullable = false)
    private BigDecimal total = Money.ZERO;

    // ── Checkout choices ─────────────────────────────────────────────────────
    public enum PaymentMethod { PREPAID, COD }

    public enum DeliveryWindow { MORNING, AFTERNOON, EVENING }

    // COD: nothing is charged up front; the driver collects the total at the door
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod = PaymentMethod.PREPAID;

    @Column(name = "coupon_code")
    private String couponCode;

    // Taken off by the coupon, GST included; the lines' discounts add up to it
    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal = Money.ZERO;

    // The day and time the customer booked; both null for standard delivery
    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_window")
    private DeliveryWindow deliveryWindow;

    // GST state code of the delivery address; decides CGST + SGST or IGST
    @Column(name = "place_of_supply", length = 2)
    private String placeOfSupply;

    // The seller's GSTIN when the order was priced, printed on its invoice
    @Column(name = "seller_gstin", length = 15)
    private String sellerGstin;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "status_changed_at", nullable = false)
    private LocalDateTime statusChangedAt = LocalDateTime.now();

    // The caller's own id for this order, unique per customer
    @Column(name = "external_reference")
    private String externalReference;

    // ── Stuck-order recovery bookkeeping ─────────────────────────────────────
    @Column(name = "recovery_attempts", nullable = false)
    @JsonIgnore
    private int recoveryAttempts;

    @Column(name = "last_recovery_at")
    @JsonIgnore
    private LocalDateTime lastRecoveryAt;

    // The saga that may compensate this order; see ownedBy
    @Column(name = "saga_id")
    @JsonIgnore
    private String sagaId;

    // ── Phase 2: Logistics & Carrier Fulfillment fields ─────────────────────────
    @Column(name = "carrier")
    private String carrier;

    @Column(name = "tracking_number")
    private String trackingNumber;

    // What the carrier charges us; the customer is not charged for shipping
    @Column(name = "shipping_cost")
    private BigDecimal shippingCost;

    // ── Phase 2: Fraud Evaluation fields ───────────────────────────────────────
    @Column(name = "fraud_score")
    private Double fraudScore;

    @Column(name = "fraud_status")
    private String fraudStatus;

    // ── Payment fields: what was charged, and whether it was given back ─────────
    @Column(name = "payment_transaction_id")
    private String paymentTransactionId;

    @Column(name = "payment_gateway")
    private String paymentGateway;

    // Set once everything charged has been given back
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    // Money given back so far, by cancellations, returns and goodwill refunds
    @Column(name = "refunded_total", nullable = false)
    private BigDecimal refundedTotal = Money.ZERO;

    // Value of units cancelled before dispatch, GST included (CANCELLATION credit notes)
    @Column(name = "cancelled_total", nullable = false)
    private BigDecimal cancelledTotal = Money.ZERO;

    // ── Returns ──────────────────────────────────────────────────────────────
    public enum ReturnStatus { NONE, PARTIAL, FULL }

    // The return window counts from here
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_status", nullable = false)
    private ReturnStatus returnStatus = ReturnStatus.NONE;

    // ── Invoice fields: assigned once, so reprints show the same number and date ─
    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    // Fixed when the order is placed; null on orders from before addresses existed
    @Embedded
    private ShippingAddress shippingAddress;

    // Financial records are never removed, only hidden
    @Column(name = "deleted_at")
    @JsonIgnore
    private LocalDateTime deletedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    /** A new server-issued order id. */
    public static String newId() {
        return "ORD-" + UUID.randomUUID();
    }

    public Long getVersion() { return version; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    /**
     * Moves the order to its next status, refusing any move the life cycle does not allow.
     */
    public void moveTo(OrderStatus next) {
        if (status == null || !status.canMoveTo(next)) {
            throw new InvalidOrderStateException("Order " + orderId + " cannot move from " + status + " to " + next);
        }
        status = next;
        statusChangedAt = LocalDateTime.now();
        if (next == OrderStatus.DELIVERED) {
            deliveredAt = statusChangedAt;
        }
    }

    public LocalDateTime getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(LocalDateTime statusChangedAt) { this.statusChangedAt = statusChangedAt; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public int getRecoveryAttempts() { return recoveryAttempts; }
    public void setRecoveryAttempts(int recoveryAttempts) { this.recoveryAttempts = recoveryAttempts; }

    public LocalDateTime getLastRecoveryAt() { return lastRecoveryAt; }
    public void setLastRecoveryAt(LocalDateTime lastRecoveryAt) { this.lastRecoveryAt = lastRecoveryAt; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<OrderItem> getItems() { return items; }
    public void setItems(List<OrderItem> items) { this.items = items; }

    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }

    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }

    public BigDecimal getShippingCost() { return shippingCost; }
    public void setShippingCost(BigDecimal shippingCost) { this.shippingCost = shippingCost == null ? null : Money.of(shippingCost); }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = Money.of(subtotal); }

    public BigDecimal getCgstTotal() { return cgstTotal; }
    public void setCgstTotal(BigDecimal cgstTotal) { this.cgstTotal = Money.of(cgstTotal); }

    public BigDecimal getSgstTotal() { return sgstTotal; }
    public void setSgstTotal(BigDecimal sgstTotal) { this.sgstTotal = Money.of(sgstTotal); }

    public BigDecimal getIgstTotal() { return igstTotal; }
    public void setIgstTotal(BigDecimal igstTotal) { this.igstTotal = Money.of(igstTotal); }

    public BigDecimal getTaxTotal() { return taxTotal; }
    public void setTaxTotal(BigDecimal taxTotal) { this.taxTotal = Money.of(taxTotal); }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = Money.of(total); }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod == null ? PaymentMethod.PREPAID : paymentMethod;
    }

    /** Paid in cash at the door. */
    public boolean isCashOnDelivery() {
        return paymentMethod == PaymentMethod.COD;
    }

    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }

    public BigDecimal getDiscountTotal() { return discountTotal; }
    public void setDiscountTotal(BigDecimal discountTotal) { this.discountTotal = Money.of(discountTotal); }

    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }

    public DeliveryWindow getDeliveryWindow() { return deliveryWindow; }
    public void setDeliveryWindow(DeliveryWindow deliveryWindow) { this.deliveryWindow = deliveryWindow; }

    public String getPlaceOfSupply() { return placeOfSupply; }
    public void setPlaceOfSupply(String placeOfSupply) { this.placeOfSupply = placeOfSupply; }

    public String getSellerGstin() { return sellerGstin; }
    public void setSellerGstin(String sellerGstin) { this.sellerGstin = sellerGstin; }

    public Double getFraudScore() { return fraudScore; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }

    public String getFraudStatus() { return fraudStatus; }
    public void setFraudStatus(String fraudStatus) { this.fraudStatus = fraudStatus; }

    public String getPaymentTransactionId() { return paymentTransactionId; }
    public void setPaymentTransactionId(String paymentTransactionId) { this.paymentTransactionId = paymentTransactionId; }

    public String getPaymentGateway() { return paymentGateway; }
    public void setPaymentGateway(String paymentGateway) { this.paymentGateway = paymentGateway; }

    public LocalDateTime getRefundedAt() { return refundedAt; }

    public BigDecimal getRefundedTotal() { return refundedTotal; }
    public void setRefundedTotal(BigDecimal refundedTotal) { this.refundedTotal = Money.of(refundedTotal); }

    /**
     * What can still be refunded: what was charged, less what was already given back. A prepaid
     * order was charged its total (a cancellation refund is in refundedTotal); cash on delivery
     * collects only what was due after cancellations.
     */
    public BigDecimal refundableAmount() {
        BigDecimal charged = isCashOnDelivery() ? amountDue() : total;
        return charged.subtract(refundedTotal);
    }

    /** The total less cancelled units: what the customer pays for the goods they keep. */
    @com.fasterxml.jackson.annotation.JsonProperty(value = "amountDue", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public BigDecimal amountDue() {
        return total.subtract(cancelledTotal);
    }

    public BigDecimal getCancelledTotal() { return cancelledTotal; }
    public void setCancelledTotal(BigDecimal cancelledTotal) { this.cancelledTotal = Money.of(cancelledTotal); }

    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }

    public ReturnStatus getReturnStatus() { return returnStatus; }
    public void setReturnStatus(ReturnStatus returnStatus) { this.returnStatus = returnStatus; }
    public void setRefundedAt(LocalDateTime refundedAt) { this.refundedAt = refundedAt; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }

    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(ShippingAddress shippingAddress) { this.shippingAddress = shippingAddress; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public String getSagaId() { return sagaId; }

    /**
     * Hands the order to a saga: from now on only that saga's compensations act on it.
     * Null takes it away from every saga, as recovery does when it takes an order over.
     */
    public void setSagaId(String sagaId) { this.sagaId = sagaKey(sagaId); }

    /**
     * True when the saga is the one working on this order. An LRA id is the coordinator's URL
     * for it, and the host part depends on how the coordinator was reached, so only the
     * last part of the id is compared.
     */
    public boolean ownedBy(String sagaId) {
        return this.sagaId != null && this.sagaId.equals(sagaKey(sagaId));
    }

    private static String sagaKey(String sagaId) {
        return sagaId == null ? null : sagaId.substring(sagaId.lastIndexOf('/') + 1);
    }

    /**
     * Sets the order's amounts from its lines. Runs before every save, so the stored totals
     * always match the lines, whichever code changed them.
     */
    @PrePersist
    @PreUpdate
    public void recalculateTotals() {
        // Some orders from before line items were stored have none; their stored amounts stand
        if (items == null || items.isEmpty()) {
            return;
        }
        BigDecimal taxable = Money.ZERO, cgst = Money.ZERO, sgst = Money.ZERO, igst = Money.ZERO, gross = Money.ZERO;
        BigDecimal discount = Money.ZERO;
        for (OrderItem item : items) {
            discount = discount.add(item.getDiscount());
            taxable = taxable.add(item.getTaxableValue());
            cgst = cgst.add(item.getCgstAmount());
            sgst = sgst.add(item.getSgstAmount());
            igst = igst.add(item.getIgstAmount());
            gross = gross.add(item.getLineTotal());
        }
        subtotal = taxable;
        discountTotal = discount;
        cgstTotal = cgst;
        sgstTotal = sgst;
        igstTotal = igst;
        taxTotal = cgst.add(sgst).add(igst);
        total = gross;
    }
}
