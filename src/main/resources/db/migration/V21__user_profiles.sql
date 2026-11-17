-- Personal details beyond name, email and phone (those stay on app_users), and the profile photo.
-- One row per user, made the first time they save their profile. Which fields apply depends on the
-- user's roles: customers, delivery users (drivers) and staff each have their own.
CREATE TABLE user_profiles (
    user_id                 VARCHAR(255) PRIMARY KEY REFERENCES app_users (user_id),
    -- Square JPEG in the photo bucket; new key on every upload
    photo_key               VARCHAR(300),
    photo_updated_at        TIMESTAMP(6),
    -- Customers
    date_of_birth           DATE,
    gender                  VARCHAR(20) CHECK (gender IN ('FEMALE', 'MALE', 'OTHER', 'PREFER_NOT_TO_SAY')),
    -- Delivery users
    vehicle_type            VARCHAR(10) CHECK (vehicle_type IN ('BIKE', 'SCOOTER', 'EV_SCOOTER', 'CYCLE', 'CAR', 'VAN', 'ON_FOOT')),
    vehicle_number          VARCHAR(12),
    licence_number          VARCHAR(16),
    emergency_contact_name  VARCHAR(100),
    emergency_contact_phone VARCHAR(20),
    upi_id                  VARCHAR(100),
    -- Staff
    job_title               VARCHAR(100),
    department              VARCHAR(100),
    updated_at              TIMESTAMP(6) NOT NULL
);
