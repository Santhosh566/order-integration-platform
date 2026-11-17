package com.example.orders;

import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.ShippingQuote;
import com.example.orders.service.CarrierService;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Orders on different partitions of order.created are processed side by side.
 */
@TestPropertySource(properties = {
        // One consumer per partition, so two partitions never share a consumer
        "orders.kafka.order-partitions=6",
        "orders.kafka.order-consumers=6"
})
class PipelineConcurrencyIT extends IntegrationTestBase {

    private static final int PARTITIONS = 6;

    @MockitoSpyBean CarrierService carrierService;

    @Test
    void aSlowOrder_doesNotHoldUpOrdersOnOtherPartitions() throws Exception {
        String slowSku = newSku(10);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(call -> {
            released.await(60, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(carrierService).bookWinningCarrier(forSku(slowSku), any(ShippingQuote.class));

        String slowOrder = placeOrder(slowSku, 1, 10.0);
        awaitStatus(slowOrder, OrderStatus.CONFIRMED);

        // Order ids are random, so place orders until one lands on another partition
        String sku = newSku(50);
        String otherOrder;
        do {
            otherOrder = placeOrder(sku, 1, 10.0);
        } while (partitionOf(otherOrder) == partitionOf(slowOrder));

        try {
            awaitStatus(otherOrder, OrderStatus.DELIVERED);
            assertThat(order(slowOrder).getStatus()).as("still held up").isEqualTo(OrderStatus.CONFIRMED);
        } finally {
            released.countDown();
        }
        awaitStatus(slowOrder, OrderStatus.DELIVERED);
    }

    /** The partition Kafka's default partitioner picks for an order's key. */
    private static int partitionOf(String orderId) {
        return Utils.toPositive(Utils.murmur2(orderId.getBytes(StandardCharsets.UTF_8))) % PARTITIONS;
    }

    private static Order forSku(String sku) {
        return ArgumentMatchers.argThat(o -> o != null && o.getItems().stream().anyMatch(i -> sku.equals(i.getSku())));
    }
}
