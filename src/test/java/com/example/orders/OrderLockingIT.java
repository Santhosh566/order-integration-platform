package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.service.OrderStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A transaction that read an order, then waits for its lock while another transaction changes it,
 * must work on the changed order. Saving its earlier copy would undo the other change.
 */
class OrderLockingIT extends IntegrationTestBase {

    @Autowired OrderStateService orderStateService;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void lockingReadsTheOrderAgain_soAnEarlierCopyCannotUndoAConcurrentChange() throws Exception {
        String orderId = placeOrder(newSku(10), 1, 25.0);
        awaitStatus(orderId, OrderStatus.SHIPPED);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Another transaction holds the lock and will change the order
        CompletableFuture<Void> other = new CompletableFuture<>();
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    Order o = orderStateService.lock(orderId);
                    locked.countDown();
                    sleep(500);
                    o.setCarrier("Changed elsewhere");
                });
                other.complete(null);
            } catch (Exception e) {
                other.completeExceptionally(e);
            }
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        String seen = tx.execute(status -> {
            orderRepository.findById(orderId).orElseThrow();      // an earlier read, as the invoice step did
            Order o = orderStateService.lock(orderId);              // waits for the other transaction
            o.setInvoiceNumber(o.getInvoiceNumber());               // and saves, as the invoice step does
            orderRepository.save(o);
            return o.getCarrier();
        });
        other.get(10, TimeUnit.SECONDS);

        assertThat(seen).isEqualTo("Changed elsewhere");
        assertThat(order(orderId).getCarrier()).isEqualTo("Changed elsewhere");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
