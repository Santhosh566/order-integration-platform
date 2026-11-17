package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One product photo. The pictures live in MinIO; this row holds their keys: a large one for the
 * product page and a thumbnail for cards. Position 0 is the main photo.
 */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "large_key", nullable = false, unique = true)
    private String largeKey;

    @Column(name = "thumb_key", nullable = false, unique = true)
    private String thumbKey;

    @Column(nullable = false)
    private int position;

    @Column(name = "alt_text", length = 200)
    private String altText;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    protected ProductImage() {}

    public ProductImage(String sku, String largeKey, String thumbKey, int position, String altText,
                        int width, int height, int sizeBytes, String uploadedBy) {
        this.sku = sku;
        this.largeKey = largeKey;
        this.thumbKey = thumbKey;
        this.position = position;
        this.altText = altText;
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getLargeKey() { return largeKey; }
    public String getThumbKey() { return thumbKey; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getSizeBytes() { return sizeBytes; }
    public String getUploadedBy() { return uploadedBy; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
}
