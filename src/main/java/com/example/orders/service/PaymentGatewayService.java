package com.example.orders.service;

import java.math.BigDecimal;
import com.example.orders.money.Money;
import com.example.orders.exception.InvalidOrderStateException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.PaymentResult;
import com.example.orders.model.PaymentTransaction;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.PaymentTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service managing payment gateway failover (Stripe ──► Adyen) and Saga compensation refunds.
 */
@Service("paymentGatewayService")
public class PaymentGatewayService {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayService.class);

    private final OrderRepository orderRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    public PaymentGatewayService(OrderRepository orderRepository,
                                 PaymentTransactionRepository paymentTransactionRepository) {
        this.orderRepository = orderRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
    }

    /**
     * Primary Payment Gateway: Stripe.
     * Simulates gateway outage when customerId contains 'FAIL-STRIPE'.
     */
    public PaymentResult chargeStripe(Order order) {
        log.info("[PRIMARY GATEWAY: STRIPE] Attempting authorization for Order: {} (Customer: {}, Amount: {} {})",
                order.getOrderId(), order.getCustomerId(), order.getTotal(), order.getCurrency());

        if (order.getCustomerId() != null && order.getCustomerId().contains("FAIL-STRIPE")) {
            log.error("[PRIMARY STRIPE OUTAGE] Stripe Gateway returned HTTP 503 Service Unavailable / Gateway Timeout!");
            throw new IllegalStateException("Stripe Payment Gateway unavailable (503 Service Unavailable)");
        }

        String txnId = "TXN-STRIPE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[PRIMARY STRIPE SUCCESS] Payment approved for Order {}! TxnId: {}", order.getOrderId(), txnId);
        return PaymentResult.success(order.getOrderId(), txnId, order.getTotal());
    }

    /**
     * Secondary Backup Payment Gateway: Adyen.
     * Invoked automatically by Camel Failover Load Balancer when Stripe fails.
     */
    public PaymentResult chargeAdyen(Order order) {
        log.warn("[SECONDARY GATEWAY FAILOVER: ADYEN] Primary Stripe failed! Failover Load Balancer routing to Adyen for Order: {}",
                order.getOrderId());

        String txnId = "TXN-ADYEN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[SECONDARY ADYEN SUCCESS] Backup gateway Adyen approved payment for Order {}! TxnId: {}",
                order.getOrderId(), txnId);
        return PaymentResult.success(order.getOrderId(), txnId, order.getTotal());
    }

    /**
     * Distributed Saga Compensating Action:
     * Triggered by the saga coordinator when a later saga step fails, or when the LRA
     * coordinator times the saga out because its pod stopped. Refunds only a paid order that
     * is still in this saga's hands, so a late or repeated call never refunds a shipped order.
     */
    @Transactional
    public void compensateCharge(String orderId, String sagaId) {
        orderRepository.findForUpdate(orderId).ifPresent(order -> {
            if (!order.ownedBy(sagaId)) {
                log.warn("[COMPENSATION] Order {} is not in saga {}. Not refunding.", orderId, sagaId);
            } else if (order.getStatus() == OrderStatus.CONFIRMED) {
                issueRefund(order);
            }
        });
    }

    /**
     * Gives back everything not refunded yet, at most once. The caller holds the order's row lock.
     */
    public void issueRefund(Order order) {
        if (!isCharged(order) || order.getRefundedAt() != null) {
            return;
        }
        BigDecimal left = order.refundableAmount();
        if (left.signum() > 0) {
            log.warn("[REFUND] Refunding {} {} for Order: {} (Transaction: {})",
                    left, order.getCurrency(), order.getOrderId(), order.getPaymentTransactionId());
            recordRefund(order, left);
        }
        order.setRefundedAt(LocalDateTime.now());
    }

    /**
     * Gives back part of the payment, for a return or a goodwill refund. The caller holds the
     * order's row lock, so two refunds cannot both pass the limit check.
     *
     * @return the refund's payment record
     * @throws InvalidOrderStateException when the order was not paid or the amount is more than is left
     */
    public PaymentTransaction refundPart(Order order, BigDecimal amount) {
        if (!isCharged(order)) {
            throw new InvalidOrderStateException("Order " + order.getOrderId() + " was not paid, so nothing can be refunded");
        }
        if (!Money.isPositive(amount) || amount.compareTo(order.refundableAmount()) > 0) {
            throw new InvalidOrderStateException("Refund of " + Money.format(amount, order.getCurrency())
                    + " is more than the " + Money.format(order.refundableAmount(), order.getCurrency())
                    + " still refundable on order " + order.getOrderId());
        }
        log.warn("[REFUND] Partial refund of {} {} for Order: {}", amount, order.getCurrency(), order.getOrderId());
        PaymentTransaction refund = recordRefund(order, amount);
        if (order.refundableAmount().signum() == 0) {
            order.setRefundedAt(LocalDateTime.now());
        }
        return refund;
    }

    private PaymentTransaction recordRefund(Order order, BigDecimal amount) {
        order.setRefundedTotal(order.getRefundedTotal().add(amount));
        return paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.REFUND,
                order.getPaymentGateway(), "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                amount, order.getCurrency()));
    }

    /**
     * Money was taken. A cash-on-delivery order is paid only once the driver collected the cash, so
     * cancelling or failing one before that refunds nothing.
     */
    static boolean isCharged(Order order) {
        if (order.isCashOnDelivery()) {
            return order.getPaymentTransactionId() != null;
        }
        return order.getPaymentTransactionId() != null || order.getStatus().isRevenue();
    }

    /** Gateway name of cash collected at the door; a refund of it goes by bank transfer. */
    public static final String CASH = "CASH_ON_DELIVERY";

    /**
     * The driver collected the amount due (the total less cancelled units) in cash. Recorded once; the caller holds the order's row lock.
     */
    public void recordCashCollected(Order order, String collectedBy) {
        if (!order.isCashOnDelivery() || order.getPaymentTransactionId() != null) {
            return;
        }
        String receipt = "COD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        order.setPaymentTransactionId(receipt);
        order.setPaymentGateway(CASH);
        paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.CHARGE,
                CASH, receipt, order.amountDue(), order.getCurrency()));
        log.info("[PAYMENT] {} collected {} {} in cash for Order {} (receipt {})",
                collectedBy, order.amountDue(), order.getCurrency(), order.getOrderId(), receipt);
    }

    /**
     * Puts a successful charge on the order and in the payment history. The caller holds the order's row lock.
     */
    public void recordCharge(Order order, PaymentResult payment) {
        order.setPaymentTransactionId(payment.getTransactionId());
        order.setPaymentGateway(payment.getTransactionId().startsWith("TXN-ADYEN") ? "ADYEN" : "STRIPE");
        paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.CHARGE,
                order.getPaymentGateway(), payment.getTransactionId(), payment.getAmount(), order.getCurrency()));
    }
}
