package com.example.orders.delivery;

import com.example.orders.money.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * What a driver earns per finished attempt. A failed attempt pays only when the driver went to the
 * door (after pickup, or on the way to a return); giving a job back before setting out pays nothing.
 * The amount is stored on the attempt as it finishes, so changing these rates never rewrites the past.
 */
@Component
public class DriverPay {

    private final BigDecimal perDelivery;
    private final BigDecimal perPickup;
    private final BigDecimal perFailedVisit;

    public DriverPay(@Value("${orders.delivery.pay.per-delivery:40}") BigDecimal perDelivery,
                     @Value("${orders.delivery.pay.per-pickup:35}") BigDecimal perPickup,
                     @Value("${orders.delivery.pay.per-failed-visit:15}") BigDecimal perFailedVisit) {
        this.perDelivery = Money.of(perDelivery);
        this.perPickup = Money.of(perPickup);
        this.perFailedVisit = Money.of(perFailedVisit);
    }

    public BigDecimal delivery() {
        return perDelivery;
    }

    public BigDecimal pickup() {
        return perPickup;
    }

    /** @param visited true when the driver was at (or on the way to) the customer's door */
    public BigDecimal failed(boolean visited) {
        return visited ? perFailedVisit : Money.of(BigDecimal.ZERO);
    }
}
