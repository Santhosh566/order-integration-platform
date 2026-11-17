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

    /** Gateway name of money taken from, or given back to, the customer's wallet. */
    public static final String WALLET = "WALLET";

    private final OrderRepository orderRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final WalletService wallets;

    public PaymentGatewayService(OrderRepository orderRepository,
                                 PaymentTransactionRepository paymentTransactionRepository,
                                 WalletService wallets) {
        this.orderRepository = orderRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.wallets = wallets;
    }

    /** The wallet pays the whole order, so no card is charged. */
    public boolean paidByWalletAlone(Order order) {
        return order.getWalletAmount().signum() > 0 && order.cardAmount().signum() == 0;
    }

    /**
     * Primary Payment Gateway: Stripe.
     * Simulates gateway outage when customerId contains 'FAIL-STRIPE'.
     */
    public PaymentResult chargeStripe(Order order) {
        log.info("[PRIMARY GATEWAY: STRIPE] Attempting authorization for Order: {} (Customer: {}, Amount: {} {})",
                order.getOrderId(), order.getCustomerId(), order.cardAmount(), order.getCurrency());

        if (order.getCustomerId() != null && order.getCustomerId().contains("FAIL-STRIPE")) {
            log.error("[PRIMARY STRIPE OUTAGE] Stripe Gateway returned HTTP 503 Service Unavailable / Gateway Timeout!");
            throw new IllegalStateException("Stripe Payment Gateway unavailable (503 Service Unavailable)");
        }

        String txnId = "TXN-STRIPE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[PRIMARY STRIPE SUCCESS] Payment approved for Order {}! TxnId: {}", order.getOrderId(), txnId);
        return PaymentResult.success(order.getOrderId(), txnId, order.cardAmount());
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
        return PaymentResult.success(order.getOrderId(), txnId, order.cardAmount());
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

    /**
     * Gives the amount back: to the card first, and only what the card did not pay to the wallet. A customer
     * who paid partly from the wallet and cancels one item gets it back on the card they paid with.
     *
     * @return the card's refund record, or the wallet's when nothing went to the card
     */
    private PaymentTransaction recordRefund(Order order, BigDecimal amount) {
        BigDecimal toCard = amount.min(order.cardRefundable());
        BigDecimal toWallet = amount.subtract(toCard);
        order.setRefundedTotal(order.getRefundedTotal().add(amount));
        order.setLastRefundToWallet(toWallet);
        PaymentTransaction cardRefund = null;
        if (toCard.signum() > 0) {
            cardRefund = paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.REFUND,
                    order.getPaymentGateway(), "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    toCard, order.getCurrency()));
        }
        if (toWallet.signum() <= 0) {
            return cardRefund;
        }
        order.setWalletRefunded(order.getWalletRefunded().add(toWallet));
        WalletService.Entry entry = wallets.credit(order.getCustomerId(), WalletService.Kind.ORDER_REFUND, toWallet,
                order.getOrderId(), null, "Refund for order " + order.getOrderId(), null);
        log.info("[REFUND] {} {} of Order {} back to the wallet ({})", toWallet, order.getCurrency(),
                order.getOrderId(), entry.reference());
        PaymentTransaction walletRefund = paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(),
                PaymentTransaction.Kind.REFUND, WALLET, entry.reference(), toWallet, order.getCurrency()));
        return cardRefund != null ? cardRefund : walletRefund;
    }

    /**
     * Where the latest refund of `amount` went, for the customer's message: " to your original payment
     * method", " to your wallet", or both with their amounts.
     */
    public static String refundDestination(Order order, BigDecimal amount) {
        BigDecimal toWallet = order.getLastRefundToWallet();
        if (toWallet.signum() == 0) {
            return " to your original payment method";
        }
        if (toWallet.compareTo(amount) >= 0) {
            return " to your wallet";
        }
        return ": " + Money.format(amount.subtract(toWallet), order.getCurrency()) + " to your original payment method and "
                + Money.format(toWallet, order.getCurrency()) + " to your wallet";
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
        if (payment == null) {
            // Paid by the wallet alone: recordWalletPayment gives the order its payment reference
            return;
        }
        order.setPaymentTransactionId(payment.getTransactionId());
        order.setPaymentGateway(payment.getTransactionId().startsWith("TXN-ADYEN") ? "ADYEN" : "STRIPE");
        paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.CHARGE,
                order.getPaymentGateway(), payment.getTransactionId(), payment.getAmount(), order.getCurrency()));
    }

    /**
     * Takes the order's wallet part from the customer's wallet, in the same transaction as the card's charge
     * is recorded. An order paid by the wallet alone takes the wallet's reference as its payment reference.
     *
     * @throws WalletService.InsufficientBalanceException when the wallet was spent elsewhere after checkout
     */
    public void recordWalletPayment(Order order) {
        if (order.getWalletAmount().signum() == 0) {
            return;
        }
        WalletService.Entry entry = wallets.debit(order.getCustomerId(), WalletService.Kind.ORDER_PAYMENT,
                order.getWalletAmount(), order.getOrderId(), "Paid for order " + order.getOrderId(), null);
        if (order.getPaymentTransactionId() == null) {
            order.setPaymentTransactionId(entry.reference());
            order.setPaymentGateway(WALLET);
        }
        paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.CHARGE,
                WALLET, entry.reference(), order.getWalletAmount(), order.getCurrency()));
        log.info("[PAYMENT] {} {} of Order {} paid from the wallet ({})", order.getWalletAmount(), order.getCurrency(),
                order.getOrderId(), entry.reference());
    }

    /**
     * The card was charged but the order cannot go on (cancelled during the charge, or the wallet no longer
     * covers its part), and the wallet was not touched: the card's charge alone goes back, and the order is
     * marked refunded so nothing is given back twice. A dead letter replay starts the payment afresh.
     */
    public void refundCardCharge(Order order, PaymentResult payment) {
        if (payment == null) {
            return;
        }
        recordCharge(order, payment);
        log.warn("[REFUND] Refunding the card's {} {} for Order {} (Transaction: {})", payment.getAmount(),
                order.getCurrency(), order.getOrderId(), payment.getTransactionId());
        order.setRefundedTotal(order.getRefundedTotal().add(payment.getAmount()));
        paymentTransactionRepository.save(new PaymentTransaction(order.getOrderId(), PaymentTransaction.Kind.REFUND,
                order.getPaymentGateway(), "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                payment.getAmount(), order.getCurrency()));
        order.setRefundedAt(LocalDateTime.now());
    }
}
