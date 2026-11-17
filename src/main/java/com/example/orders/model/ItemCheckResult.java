package com.example.orders.model;

public class ItemCheckResult {

    private String sku;
    private int requestedQuantity;
    private int availableQuantity;
    private boolean inStock;

    public ItemCheckResult() {
    }

    public ItemCheckResult(String sku, int requestedQuantity, int availableQuantity, boolean inStock) {
        this.sku = sku;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
        this.inStock = inStock;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public void setRequestedQuantity(int requestedQuantity) {
        this.requestedQuantity = requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(int availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public boolean isInStock() {
        return inStock;
    }

    public void setInStock(boolean inStock) {
        this.inStock = inStock;
    }

    @Override
    public String toString() {
        return "ItemCheckResult{" +
                "sku='" + sku + '\'' +
                ", requested=" + requestedQuantity +
                ", available=" + availableQuantity +
                ", inStock=" + inStock +
                '}';
    }
}
