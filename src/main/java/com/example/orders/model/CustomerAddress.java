package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * An address saved on a customer's account. Each customer has at most one default.
 */
@Entity
@Table(name = "customer_addresses")
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "address_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String label;

    @Embedded
    private ShippingAddress address;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected CustomerAddress() {}

    public CustomerAddress(String userId, String label, ShippingAddress address, boolean defaultAddress) {
        this.userId = userId;
        this.label = label;
        this.address = address;
        this.defaultAddress = defaultAddress;
    }

    public Long getId() { return id; }

    public String getUserId() { return userId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public ShippingAddress getAddress() { return address; }
    public void setAddress(ShippingAddress address) { this.address = address; }

    public boolean isDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(boolean defaultAddress) { this.defaultAddress = defaultAddress; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
