package com.example.orders;

import java.math.BigDecimal;
import com.example.orders.model.CustomerNotification;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.OrderStatus;
import com.example.orders.repository.CustomerNotificationRepository;
import com.example.orders.service.OrderStateService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Customer messages wait in the database until the mail server takes them.
 * Here the mail server is never reachable.
 */
@TestPropertySource(properties = {
        // Nothing listens on port 1
        "orders.mail.port=1",
        "orders.notifications.retry-delay-ms=200",
        "orders.notifications.max-attempts=3"
})
class NotificationOutboxIT extends IntegrationTestBase {

    @Autowired CustomerNotificationRepository notifications;
    @Autowired OrderStateService orderStateService;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void mailDown_retriesThenGivesUp_andTheOrderCarriesOn() {
        String orderId = placeOrder(newSku(10), 1, 10.0);

        awaitStatus(orderId, OrderStatus.DELIVERED);
        Awaitility.await("the payment message to use up its attempts").atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(notifications.findByOrderIdOrderByCreatedAtAsc(orderId))
                .filteredOn(n -> n.getSubject().startsWith("We received your payment")).singleElement()
                .satisfies(n -> {
                    assertThat(n.getStatus()).isEqualTo(CustomerNotification.Status.FAILED);
                    assertThat(n.getAttempts()).isEqualTo(3);
                    assertThat(n.getLastError()).isNotBlank();
                }));
    }

    @Test
    void aChangeThatRollsBack_leavesNoMessage() {
        Order order = new Order();
        order.setOrderId(Order.newId());
        order.setCustomerId("CUST-100");
        order.setItems(new ArrayList<>(List.of(new OrderItem(newSku(5), 1, new BigDecimal("10.0")))));
        order.recalculateTotals();
        order.setStatus(OrderStatus.INVENTORY_RESERVED);
        order.setCreatedAt(LocalDateTime.now());
        String orderId = orderRepository.save(order).getOrderId();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderStateService.moveTo(orderStateService.lock(orderId), OrderStatus.PAYMENT_FAILED);
            throw new IllegalStateException("something later in the transaction failed");
        })).hasMessageContaining("later");

        assertThat(order(orderId).getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(notifications.findByOrderIdOrderByCreatedAtAsc(orderId)).isEmpty();
    }
}
