package com.example.orders.service;

import java.math.BigDecimal;
import com.example.orders.money.Money;
import com.example.orders.model.Order;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.ShippingQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service providing carrier quotes and fulfillment dispatch for Scatter-Gather EIP.
 */
@Service("carrierService")
public class CarrierService {

    private static final Logger log = LoggerFactory.getLogger(CarrierService.class);

    private final OrderStateService orderStateService;

    public CarrierService(OrderStateService orderStateService) {
        this.orderStateService = orderStateService;
    }

    /**
     * Carrier A: FedEx quote generator
     */
    public ShippingQuote quoteFedEx(Order order) {
        BigDecimal cost = Money.of("149.00");
        int days = 2;
        log.info("[SCATTER-GATHER: FEDEX] Generated quote for Order {}: ₹{} ({} days, 2-Day Air)",
                order.getOrderId(), cost, days);
        return new ShippingQuote("FedEx", cost, days, "FedEx 2-Day Air");
    }

    /**
     * Carrier B: DHL quote generator
     */
    public ShippingQuote quoteDHL(Order order) {
        BigDecimal cost = Money.of("99.00");
        int days = 3;
        log.info("[SCATTER-GATHER: DHL] Generated quote for Order {}: ₹{} ({} days, Express Economy)",
                order.getOrderId(), cost, days);
        return new ShippingQuote("DHL", cost, days, "DHL Express Economy");
    }

    /**
     * Carrier C: UPS quote generator
     */
    public ShippingQuote quoteUPS(Order order) {
        BigDecimal cost = Money.of("179.00");
        int days = 1;
        log.info("[SCATTER-GATHER: UPS] Generated quote for Order {}: ₹{} ({} day, Next Day Air)",
                order.getOrderId(), cost, days);
        return new ShippingQuote("UPS", cost, days, "UPS Next Day Air");
    }

    /**
     * Books shipping with the winning carrier selected by the Gather Aggregator.
     *
     * @return the booked order, or null when it is no longer CONFIRMED (it was cancelled meanwhile)
     */
    @Transactional
    public Order bookWinningCarrier(Order event, ShippingQuote winningQuote) {
        // The Kafka message is a snapshot, so the booking is applied to the stored order
        Order order = orderStateService.lock(event.getOrderId());
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            log.warn("[CARRIER] Order {} is {}, not booking a carrier", order.getOrderId(), order.getStatus());
            return null;
        }

        String trackingNumber = "TRK-" + winningQuote.getCarrier().toUpperCase() + "-" +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        log.info("[CARRIER BOOKED] Winning Carrier: {} (₹{}) selected for Order {}. Generated Tracking #: {}",
                winningQuote.getCarrier(), winningQuote.getCost(), order.getOrderId(), trackingNumber);

        order.setCarrier(winningQuote.getCarrier());
        order.setTrackingNumber(trackingNumber);
        order.setShippingCost(winningQuote.getCost());
        orderStateService.moveTo(order, OrderStatus.SHIPPED);
        return order;
    }
}
