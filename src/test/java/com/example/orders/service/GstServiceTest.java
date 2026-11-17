package com.example.orders.service;

import com.example.orders.exception.InvalidOrderException;
import com.example.orders.model.Order;
import com.example.orders.model.OrderItem;
import com.example.orders.model.ShippingAddress;
import com.example.orders.money.IndianState;
import com.example.orders.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GST without Spring or a database. The seller is registered in Karnataka (29).
 */
class GstServiceTest {

    private final GstService inclusive = new GstService("Karnataka", "29AABCO1234F1Z5", "INR", true);
    private final GstService exclusive = new GstService("KA", "29AABCO1234F1Z5", "INR", false);

    @Test
    void sameState_paysEqualCgstAndSgst_andTheLineTotalsItsShownPrice() {
        Order order = order("Karnataka", line("8499.00", 1, "18"));

        inclusive.apply(order);

        OrderItem item = order.getItems().get(0);
        // 8499 / 1.18 = 7202.54; 9% of that is 648.23 each way
        assertThat(item.getCgstAmount()).isEqualByComparingTo("648.23");
        assertThat(item.getSgstAmount()).isEqualByComparingTo("648.23");
        assertThat(item.getIgstAmount()).isEqualByComparingTo("0");
        assertThat(item.getTaxableValue()).isEqualByComparingTo("7202.54");
        assertThat(item.getLineTotal()).isEqualByComparingTo("8499.00");
        assertThat(order.getTotal()).isEqualByComparingTo("8499.00");
        assertThat(order.getTaxTotal()).isEqualByComparingTo("1296.46");
        assertThat(order.getPlaceOfSupply()).isEqualTo("29");
        assertThat(order.getCurrency()).isEqualTo("INR");
        assertThat(order.getSellerGstin()).isEqualTo("29AABCO1234F1Z5");
    }

    @Test
    void otherState_paysIgst() {
        Order order = order("MH", line("3299.00", 2, "18"));

        inclusive.apply(order);

        OrderItem item = order.getItems().get(0);
        // 6598 / 1.18 = 5591.53; 18% of that is 1006.47
        assertThat(item.getIgstAmount()).isEqualByComparingTo("1006.47");
        assertThat(item.getCgstAmount()).isEqualByComparingTo("0");
        assertThat(item.getTaxableValue()).isEqualByComparingTo("5591.53");
        assertThat(order.getTotal()).isEqualByComparingTo("6598.00");
        assertThat(order.getPlaceOfSupply()).isEqualTo("27");
    }

    @Test
    void everyLineAndTheOrder_addUpToThePaisa() {
        // Awkward amounts on purpose: odd paise, several rates, quantities above one
        Order order = order("Karnataka",
                line("33.33", 3, "18"), line("0.99", 7, "5"), line("1999.99", 1, "40"), line("10.01", 13, "0"));

        inclusive.apply(order);

        BigDecimal lines = Money.ZERO;
        for (OrderItem item : order.getItems()) {
            assertThat(item.getLineTotal()).isEqualByComparingTo(item.grossAmount());
            assertThat(item.getTaxableValue().add(item.taxAmount())).isEqualByComparingTo(item.getLineTotal());
            assertThat(item.getCgstAmount()).isEqualByComparingTo(item.getSgstAmount());
            lines = lines.add(item.getLineTotal());
        }
        assertThat(order.getTotal()).isEqualByComparingTo(lines);
        assertThat(order.getSubtotal().add(order.getTaxTotal())).isEqualByComparingTo(order.getTotal());
        assertThat(order.getTaxTotal())
                .isEqualByComparingTo(order.getCgstTotal().add(order.getSgstTotal()).add(order.getIgstTotal()));
    }

    @Test
    void zeroRatedLine_paysNoTax() {
        Order order = order("Delhi", line("250.00", 2, "0"));

        inclusive.apply(order);

        assertThat(order.getTaxTotal()).isEqualByComparingTo("0");
        assertThat(order.getTotal()).isEqualByComparingTo("500.00");
    }

    @Test
    void pricesWithoutTax_addTheTaxOnTop() {
        Order order = order("Karnataka", line("1000.00", 1, "18"));

        exclusive.apply(order);

        assertThat(order.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(order.getCgstTotal()).isEqualByComparingTo("90.00");
        assertThat(order.getSgstTotal()).isEqualByComparingTo("90.00");
        assertThat(order.getTotal()).isEqualByComparingTo("1180.00");
    }

    @Test
    void deliveriesOutsideIndia_orToAnUnknownState_areRefused() {
        Order abroad = order("NY", line("100.00", 1, "18"));
        abroad.getShippingAddress().setCountry("US");
        assertThatThrownBy(() -> inclusive.apply(abroad)).isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("within India");

        Order unknown = order("Atlantis", line("100.00", 1, "18"));
        assertThatThrownBy(() -> inclusive.apply(unknown)).isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("state");
    }

    @Test
    void aSellerGstinFromAnotherState_stopsTheApplication() {
        assertThatThrownBy(() -> new GstService("Karnataka", "27AABCO1234F1Z5", "INR", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void statesAreFoundByNameAbbreviationOrCode() {
        assertThat(IndianState.find("tamil nadu")).contains(IndianState.TAMIL_NADU);
        assertThat(IndianState.find("TN")).contains(IndianState.TAMIL_NADU);
        assertThat(IndianState.find("33")).contains(IndianState.TAMIL_NADU);
        assertThat(IndianState.find("Jammu & Kashmir")).contains(IndianState.JAMMU_AND_KASHMIR);
        assertThat(IndianState.find("Narnia")).isEmpty();
    }

    @Test
    void rupeesUseIndianDigitGrouping() {
        assertThat(Money.format(new BigDecimal("12345678.5"), "INR")).isEqualTo("₹1,23,45,678.50");
        assertThat(Money.format(new BigDecimal("999"), "INR")).isEqualTo("₹999.00");
        assertThat(Money.format(new BigDecimal("1000"), "INR")).isEqualTo("₹1,000.00");
        assertThat(Money.format(new BigDecimal("1234567.891"), "USD")).isEqualTo("USD 1,234,567.89");
        assertThat(Money.formatCode(new BigDecimal("84999"), "INR")).isEqualTo("INR 84,999.00");
    }

    @Test
    void financialYear_runsFromAprilToMarch() {
        assertThat(InvoiceNumberService.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("25-26");
        assertThat(InvoiceNumberService.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("26-27");
        assertThat(InvoiceNumberService.financialYear(LocalDate.of(2099, 12, 31))).isEqualTo("99-00");
    }

    private static OrderItem line(String price, int quantity, String gstRate) {
        OrderItem item = new OrderItem("SKU-" + price, quantity, new BigDecimal(price));
        item.setGstRate(new BigDecimal(gstRate));
        return item;
    }

    private static Order order(String state, OrderItem... items) {
        ShippingAddress address = new ShippingAddress();
        address.setState(state);
        address.setCountry("IN");
        Order order = new Order();
        order.setShippingAddress(address);
        order.setItems(new ArrayList<>(List.of(items)));
        return order;
    }
}
