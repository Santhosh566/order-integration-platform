package com.example.orders.model.csv;

import java.math.BigDecimal;
import org.apache.camel.dataformat.bindy.annotation.CsvRecord;
import org.apache.camel.dataformat.bindy.annotation.DataField;

/**
 * Bindy-annotated model for B2B partner dropship CSV lines.
 * Example CSV line:
 * ORD-CSV-001,CUST-B2B-01,WIDGET-A,2,45.00
 */
@CsvRecord(separator = ",", skipFirstLine = true, generateHeaderColumns = false)
public class CsvOrderItemRecord {

    @DataField(pos = 1)
    private String orderId;

    @DataField(pos = 2)
    private String customerId;

    @DataField(pos = 3)
    private String sku;

    @DataField(pos = 4)
    private int quantity;

    @DataField(pos = 5)
    private BigDecimal price;

    public CsvOrderItemRecord() {}

    public CsvOrderItemRecord(String orderId, String customerId, String sku, int quantity, BigDecimal price) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.sku = sku;
        this.quantity = quantity;
        this.price = price;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    @Override
    public String toString() {
        return "CsvOrderItemRecord{" +
                "orderId='" + orderId + '\'' +
                ", customerId='" + customerId + '\'' +
                ", sku='" + sku + '\'' +
                ", quantity=" + quantity +
                ", price=" + price +
                '}';
    }
}
