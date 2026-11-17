package com.example.orders.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * A customer, delivery user or admin. Orders point here through their customer id.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String email;

    private String phone;

    // The Keycloak user id ("sub" in the token); Keycloak holds the password
    @Column(name = "keycloak_subject")
    @JsonIgnore
    private String keycloakSubject;

    @Column(nullable = false)
    private String tier = "STANDARD";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    @Column(name = "deactivated_by")
    private String deactivatedBy;

    @Column(name = "deactivated_reason")
    private String deactivatedReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    protected AppUser() {}

    public AppUser(String userId, String fullName, Role role) {
        this.userId = userId;
        this.fullName = fullName;
        this.roles.add(role);
    }

    public String getUserId() { return userId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getKeycloakSubject() { return keycloakSubject; }
    public void setKeycloakSubject(String keycloakSubject) { this.keycloakSubject = keycloakSubject; }

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getDeactivatedAt() { return deactivatedAt; }
    public String getDeactivatedBy() { return deactivatedBy; }
    public String getDeactivatedReason() { return deactivatedReason; }

    /** Turned off by an admin: still signs in, but may not use the app. */
    public void deactivate(String by, String reason) {
        this.active = false;
        this.deactivatedAt = LocalDateTime.now();
        this.deactivatedBy = by;
        this.deactivatedReason = reason;
    }

    public void reactivate() {
        this.active = true;
        this.deactivatedAt = null;
        this.deactivatedBy = null;
        this.deactivatedReason = null;
    }

    public Set<Role> getRoles() { return roles; }
}
