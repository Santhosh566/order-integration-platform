package com.example.orders.service;

import com.example.orders.money.Money;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatusChanged;
import com.example.orders.repository.CustomerNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tells the customer about each status they care about.
 * Each message says only what has really happened: "shipped" waits until the goods left the warehouse.
 * The out-for-delivery message carries the one-time code, so DeliveryService writes it.
 *
 * Messages are saved in the transaction that changes the order and sent by the
 * customer-notification-outbox route, so a crash can delay a message but never lose it.
 * A pod that stops between sending and recording it sends that message again (at least once).
 */
@Service
public class CustomerNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerNotificationService.class);

    private final CustomerNotificationRepository notifications;
    private final int maxAttempts;
    private final long retryDelayMs;

    public CustomerNotificationService(CustomerNotificationRepository notifications,
                                       @Value("${orders.notifications.max-attempts}") int maxAttempts,
                                       @Value("${orders.notifications.retry-delay-ms}") long retryDelayMs) {
        this.notifications = notifications;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * Runs inside the transaction that changed the order, so the message commits or rolls back with it.
     * The message is composed now: the order may change again before the transaction ends.
     */
    @EventListener
    public void onOrderStatusChanged(OrderStatusChanged event) {
        CustomerNotification notification = compose(event.order());
        if (notification != null) {
            queue(notification);
        }
    }

    /** Saves a message to be sent, in the caller's transaction. */
    public void queue(CustomerNotification notification) {
        notifications.save(notification);
    }

    /**
     * Takes the messages that are due, so no other pod sends them too.
     */
    @Transactional
    public List<CustomerNotification> claimDue() {
        LocalDateTime now = LocalDateTime.now();
        int abandoned = notifications.releaseAbandoned(now.minusMinutes(5));
        if (abandoned > 0) {
            log.warn("[NOTIFICATION] Retrying {} message(s) a pod took but never finished", abandoned);
        }
        List<CustomerNotification> due = notifications.claimDue(now);
        due.forEach(notification -> notification.claim(now));
        return notifications.saveAll(due);
    }

    @Transactional
    public void markSent(String notificationId) {
        notifications.findById(notificationId).ifPresent(notification -> notification.sent(LocalDateTime.now()));
    }

    /**
     * Tries again later, waiting twice as long each time, until the last attempt.
     */
    @Transactional
    public void markFailed(String notificationId, String error) {
        notifications.findById(notificationId).ifPresent(notification -> {
            int attempt = notification.getAttempts() + 1;
            if (attempt >= maxAttempts) {
                log.error("[NOTIFICATION] Giving up on '{}' for order {} after {} attempts: {}",
                        notification.getSubject(), notification.getOrderId(), attempt, error);
                notification.failed(error, null);
            } else {
                long delay = retryDelayMs << (attempt - 1);
                log.warn("[NOTIFICATION] Could not send '{}' for order {} (attempt {}): {}. Retrying in {} s",
                        notification.getSubject(), notification.getOrderId(), attempt, error, delay / 1000);
                notification.failed(error, LocalDateTime.now().plusNanos(delay * 1_000_000));
            }
        });
    }

    static CustomerNotification compose(Order order) {
        String id = order.getOrderId();
        String amount = Money.format(order.getTotal(), order.getCurrency());
        boolean paid = order.getPaymentTransactionId() != null;
        String subject;
        String text;
        switch (order.getStatus()) {
            case CONFIRMED -> {
                if (order.isCashOnDelivery()) {
                    subject = "Your order " + id + " is confirmed";
                    text = "Your order " + id + " is confirmed and we are preparing it. You pay " + amount
                            + " in cash when it is delivered.";
                } else {
                    subject = "We received your payment for order " + id;
                    text = "We received your payment of " + amount + " for order " + id + " and are preparing it.";
                }
                if (order.getDeliveryDate() != null && order.getDeliveryWindow() != null) {
                    text += " Delivery is booked for " + order.getDeliveryDate().format(
                            java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.ENGLISH))
                            + ", " + CheckoutService.WINDOW_LABELS.get(order.getDeliveryWindow()) + ".";
                }
            }
            case IN_TRANSIT -> {
                subject = "Your order " + id + " has shipped";
                text = "Your order " + id + " is on its way. Carrier: " + order.getCarrier()
                        + ". Tracking number: " + order.getTrackingNumber() + ".";
            }
            case DELIVERY_FAILED -> {
                subject = "We could not deliver order " + id;
                text = "We could not deliver order " + id + " this time. We will try again soon.";
            }
            case RETURNED -> {
                subject = "Order " + id + " was returned to us";
                text = "We could not deliver order " + id + ", so it came back to us. "
                        + (paid ? "We refunded your payment of " + amount + "." : "No payment was taken.");
            }
            case DELIVERED -> {
                subject = "Your order " + id + " was delivered";
                text = "Your order " + id + " was delivered. Thank you for shopping with us.";
            }
            case CANCELLED -> {
                subject = "Your order " + id + " was cancelled";
                text = "Your order " + id + " was cancelled. "
                        + (order.getRefundedAt() != null ? "We refunded your payment of " + amount + "." : "No payment was taken.");
            }
            case OUT_OF_STOCK -> {
                subject = "We could not fulfil order " + id;
                text = "Some items in order " + id + " are out of stock, so we could not fulfil it. You have not been charged.";
            }
            case PAYMENT_FAILED -> {
                subject = "Payment failed for order " + id;
                text = "We could not take payment for order " + id + ", so it was not processed. You have not been charged.";
            }
            case FULFILLMENT_FAILED -> {
                subject = "We could not ship order " + id;
                text = "We could not ship order " + id + ". "
                        + (paid ? "Your payment of " + amount + " will be refunded." : "You have not been charged.");
            }
            case FRAUD_REJECTED -> {
                subject = "We could not accept order " + id;
                text = "We could not accept order " + id + ". You have not been charged. "
                        + "Please contact support if you think this is a mistake.";
            }
            default -> {
                return null;
            }
        }
        return new CustomerNotification(id, order.getCustomerId(), subject, text);
    }
}
