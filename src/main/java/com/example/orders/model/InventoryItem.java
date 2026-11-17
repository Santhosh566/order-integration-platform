package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * A product in the catalogue and its stock in the warehouse.
 *
 * Stock quantities change only through StockLedger, which records every change in stock_movements.
 * Catalogue edits go through CatalogAdminService, which checks catalogVersion so two admins
 * cannot silently overwrite each other.
 */
@Entity
@Table(name = "inventory")
public class InventoryItem {

    @Id
    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "product_name")
    private String productName;

    // What an order is charged per unit; a SKU without a price cannot be ordered
    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    // Harmonized System of Nomenclature code printed on GST invoices
    @Column(name = "hsn_code", length = 8)
    private String hsnCode;

    // GST rate in percent
    @Column(name = "gst_rate", nullable = false)
    private BigDecimal gstRate = DEFAULT_GST_RATE;

    public static final BigDecimal DEFAULT_GST_RATE = new BigDecimal("18.00");

    // A retired product cannot be ordered
    @Column(nullable = false)
    private boolean active = true;

    // Staff are alerted when available stock falls to this level; 0 means no alert
    @Column(name = "reorder_level", nullable = false)
    private int reorderLevel;

    @Column(name = "catalog_version", nullable = false)
    private int catalogVersion;

    // Shown on the product page; plain text, paragraphs separated by blank lines
    @Column(name = "description")
    private String description;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "brand", length = 80)
    private String brand;

    // Most units of this product one order may hold; null means no limit
    @Column(name = "max_per_order")
    private Integer maxPerOrder;

    // Kept up to date by ReviewService when a review is approved or hidden
    @Column(name = "rating_avg", nullable = false)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public InventoryItem() {
    }

    public InventoryItem(String sku, int availableQuantity, int reservedQuantity) {
        this.sku = sku;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = reservedQuantity;
    }

    public InventoryItem(String sku, String productName, BigDecimal unitPrice, int availableQuantity) {
        this(sku, availableQuantity, 0);
        this.productName = productName;
        this.unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
    }

    public InventoryItem(String sku, String productName, BigDecimal unitPrice, String hsnCode,
                         BigDecimal gstRate, int availableQuantity) {
        this(sku, productName, unitPrice, availableQuantity);
        this.hsnCode = hsnCode;
        this.gstRate = gstRate;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(int availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public void setReservedQuantity(int reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(int reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public int getCatalogVersion() {
        return catalogVersion;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    /** Records a catalogue edit: who, when, and a new version for the next edit to check against. */
    public void edited(String by) {
        catalogVersion++;
        updatedAt = LocalDateTime.now();
        updatedBy = by;
    }

    @Override
    public String toString() {
        return "InventoryItem{" +
                "sku='" + sku + '\'' +
                ", available=" + availableQuantity +
                ", reserved=" + reservedQuantity +
                '}';
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public Integer getMaxPerOrder() { return maxPerOrder; }
    public void setMaxPerOrder(Integer maxPerOrder) { this.maxPerOrder = maxPerOrder; }

    public BigDecimal getRatingAvg() { return ratingAvg; }
    public void setRatingAvg(BigDecimal ratingAvg) { this.ratingAvg = ratingAvg; }

    public int getRatingCount() { return ratingCount; }
    public void setRatingCount(int ratingCount) { this.ratingCount = ratingCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
