package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where a delivery goes and who receives it. Used by saved addresses and copied onto each order.
 */
@Embeddable
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShippingAddress {

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "recipient_phone")
    private String recipientPhone;

    @Column(name = "address_line1")
    private String line1;

    @Column(name = "address_line2")
    private String line2;

    private String city;

    // State code, such as NY. Decides the regional fulfillment hub.
    @Column(name = "state_region")
    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    private String country;

    public ShippingAddress() {}

    public ShippingAddress(ShippingAddress other) {
        recipientName = other.recipientName;
        recipientPhone = other.recipientPhone;
        line1 = other.line1;
        line2 = other.line2;
        city = other.city;
        state = other.state;
        postalCode = other.postalCode;
        country = other.country;
    }

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }

    public String getLine1() { return line1; }
    public void setLine1(String line1) { this.line1 = line1; }

    public String getLine2() { return line2; }
    public void setLine2(String line2) { this.line2 = line2; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    /** The address on one line, for invoices. */
    public String singleLine() {
        return Stream.of(recipientName, line1, line2, city, state, postalCode, country)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }
}
