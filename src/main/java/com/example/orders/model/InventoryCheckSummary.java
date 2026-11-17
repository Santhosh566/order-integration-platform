package com.example.orders.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated summary of stock checks for all items of an order.
 */
public class InventoryCheckSummary {

    private String orderId;
    private boolean allInStock = true;
    private List<ItemCheckResult> results = new ArrayList<>();
    private List<String> missingSkus = new ArrayList<>();

    public InventoryCheckSummary() {
    }

    public InventoryCheckSummary(String orderId) {
        this.orderId = orderId;
    }

    public void addItemResult(ItemCheckResult itemResult) {
        results.add(itemResult);
        if (!itemResult.isInStock()) {
            allInStock = false;
            missingSkus.add(itemResult.getSku());
        }
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public boolean isAllInStock() {
        return allInStock;
    }

    public boolean getAllInStock() {
        return allInStock;
    }

    public void setAllInStock(boolean allInStock) {
        this.allInStock = allInStock;
    }

    public List<ItemCheckResult> getResults() {
        return results;
    }

    public void setResults(List<ItemCheckResult> results) {
        this.results = results;
    }

    public List<String> getMissingSkus() {
        return missingSkus;
    }

    public void setMissingSkus(List<String> missingSkus) {
        this.missingSkus = missingSkus;
    }

    @Override
    public String toString() {
        return "InventoryCheckSummary{" +
                "orderId='" + orderId + '\'' +
                ", allInStock=" + allInStock +
                ", missingSkus=" + missingSkus +
                ", itemCount=" + results.size() +
                '}';
    }
}
