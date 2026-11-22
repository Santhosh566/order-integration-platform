package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.ShippingAddress;
import com.example.orders.money.IndianState;
import com.example.orders.money.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Indian GST on an order's lines.
 *
 * The place of supply is the delivery state. Delivered in the seller's own state, each line pays
 * CGST and SGST at half the rate each; delivered to another state, it pays IGST at the full rate.
 *
 * With prices-include-tax (the rule for prices shown to consumers in India), the catalogue price is
 * what the customer pays and the tax is worked out of it. CGST and SGST are always equal, and the
 * taxable value takes up the rounding, so a line still totals exactly its shown price.
 */
@Service
public class GstService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final IndianState sellerState;
    private final String sellerGstin;
    private final String currency;
    private final boolean pricesIncludeTax;

    public GstService(@Value("${orders.seller.state}") String sellerState,
                      @Value("${orders.seller.gstin}") String sellerGstin,
                      @Value("${orders.currency:INR}") String currency,
                      @Value("${orders.tax.prices-include-tax:true}") boolean pricesIncludeTax) {
        this.sellerState = IndianState.find(sellerState)
                .orElseThrow(() -> new IllegalStateException("orders.seller.state is not an Indian state: " + sellerState));
        if (sellerGstin == null || !sellerGstin.startsWith(this.sellerState.gstCode())) {
            throw new IllegalStateException("orders.seller.gstin must start with the seller's state code "
                    + this.sellerState.gstCode());
        }
        this.sellerGstin = sellerGstin;
        this.currency = currency;
        this.pricesIncludeTax = pricesIncludeTax;
    }

    /**
     * Sets every line's taxable value and tax, then the order's totals. Lines must already carry
     * their catalogue price and GST rate.
     */
    public void apply(Order order) {
        apply(order, sellerState, sellerGstin);
    }

    /**
     * As apply, for goods shipped from a registration of our own in another state (a warehouse): CGST + SGST when
     * delivered in the warehouse's state, IGST otherwise, and the warehouse's GSTIN on the order.
     */
    public void apply(Order order, IndianState shipFromState, String gstin) {
        IndianState placeOfSupply = placeOfSupply(order.getShippingAddress());
        boolean intraState = placeOfSupply == shipFromState;
        for (OrderItem item : order.getItems()) {
            taxLine(item, intraState);
        }
        order.setCurrency(currency);
        order.setPlaceOfSupply(placeOfSupply.gstCode());
        order.setSellerGstin(gstin);
        order.recalculateTotals();
    }

    /** The GST state an address is delivered to; only deliveries within India are accepted. */
    public static IndianState placeOfSupply(ShippingAddress address) {
        if (address == null) {
            throw new InvalidOrderException("shippingAddress is required");
        }
        if (!IndianState.isIndia(address.getCountry())) {
            throw new InvalidOrderException("We deliver only within India: shippingAddress.country must be IN");
        }
        return IndianState.find(address.getState())
                .orElseThrow(() -> new InvalidOrderException(
                        "shippingAddress.state must be an Indian state or union territory"));
    }

    public IndianState sellerState() {
        return sellerState;
    }

    public String sellerGstin() {
        return sellerGstin;
    }

    void taxLine(OrderItem item, boolean intraState) {
        BigDecimal rate = item.getGstRate();
        BigDecimal gross = item.grossAmount();

        // The value tax is charged on, before rounding
        BigDecimal base = pricesIncludeTax
                ? gross.multiply(HUNDRED).divide(HUNDRED.add(rate), MathContext.DECIMAL64)
                : gross;

        BigDecimal cgst = Money.ZERO, sgst = Money.ZERO, igst = Money.ZERO;
        if (intraState) {
            cgst = Money.percent(base, rate.divide(TWO));
            sgst = cgst;
        } else {
            igst = Money.percent(base, rate);
        }
        BigDecimal tax = cgst.add(sgst).add(igst);

        item.setCgstAmount(cgst);
        item.setSgstAmount(sgst);
        item.setIgstAmount(igst);
        if (pricesIncludeTax) {
            item.setTaxableValue(gross.subtract(tax));
            item.setLineTotal(gross);
        } else {
            item.setTaxableValue(gross);
            item.setLineTotal(gross.add(tax));
        }
    }
}
