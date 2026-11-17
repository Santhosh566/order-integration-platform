package com.example.orders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A user's personal details beyond name, email and phone (on AppUser), and their profile photo.
 * Customers, delivery users and staff each fill in their own part.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    public enum Gender { FEMALE, MALE, OTHER, PREFER_NOT_TO_SAY }

    public enum VehicleType {
        BIKE, SCOOTER, EV_SCOOTER, CYCLE, CAR, VAN, ON_FOOT;

        /** Needs a registration number and a driving licence on Indian roads. */
        public boolean isMotorised() {
            return this != CYCLE && this != ON_FOOT;
        }
    }

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "photo_key")
    private String photoKey;

    @Column(name = "photo_updated_at")
    private LocalDateTime photoUpdatedAt;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type")
    private VehicleType vehicleType;

    @Column(name = "vehicle_number")
    private String vehicleNumber;

    @Column(name = "licence_number")
    private String licenceNumber;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    @Column(name = "upi_id")
    private String upiId;

    @Column(name = "job_title")
    private String jobTitle;

    private String department;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected UserProfile() {}

    public UserProfile(String userId) {
        this.userId = userId;
    }

    public void touch() { this.updatedAt = LocalDateTime.now(); }

    public String getUserId() { return userId; }

    public String getPhotoKey() { return photoKey; }
    public LocalDateTime getPhotoUpdatedAt() { return photoUpdatedAt; }
    public void setPhoto(String photoKey) {
        this.photoKey = photoKey;
        this.photoUpdatedAt = photoKey == null ? null : LocalDateTime.now();
    }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }

    public VehicleType getVehicleType() { return vehicleType; }
    public void setVehicleType(VehicleType vehicleType) { this.vehicleType = vehicleType; }

    public String getVehicleNumber() { return vehicleNumber; }
    public void setVehicleNumber(String vehicleNumber) { this.vehicleNumber = vehicleNumber; }

    public String getLicenceNumber() { return licenceNumber; }
    public void setLicenceNumber(String licenceNumber) { this.licenceNumber = licenceNumber; }

    public String getEmergencyContactName() { return emergencyContactName; }
    public void setEmergencyContactName(String emergencyContactName) { this.emergencyContactName = emergencyContactName; }

    public String getEmergencyContactPhone() { return emergencyContactPhone; }
    public void setEmergencyContactPhone(String emergencyContactPhone) { this.emergencyContactPhone = emergencyContactPhone; }

    public String getUpiId() { return upiId; }
    public void setUpiId(String upiId) { this.upiId = upiId; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
