package com.example.orders.model;

/**
 * Customer profile details retrieved by the Content Enricher EIP.
 */
public class CustomerProfile {

    private String customerId;
    private String fullName;
    private String email;
    private String phone;
    private String tier;

    public CustomerProfile() {}

    public CustomerProfile(String customerId, String fullName, String email, String phone, String tier) {
        this.customerId = customerId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.tier = tier;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    @Override
    public String toString() {
        return "CustomerProfile{" +
                "customerId='" + customerId + '\'' +
                ", tier='" + tier + '\'' +
                '}';
    }
}
