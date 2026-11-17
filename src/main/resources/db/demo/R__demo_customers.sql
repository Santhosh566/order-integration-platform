-- Development accounts. Production leaves this folder out through ORDERS_FLYWAY_LOCATIONS.
-- The FAIL-STRIPE and FRAUD ids trigger the simulated gateway outage and fraud rejection.
-- Each one is linked to the Keycloak user of the same id in ops/keycloak/order-platform-realm.json,
-- where every account signs in with the password demo1234.
INSERT INTO app_users (user_id, full_name, email, phone, tier) VALUES
    ('CUST-100', 'Alice Smith', 'alice.smith@example.com', '+91-98450-00101', 'GOLD'),
    ('CUST-101', 'Bob Johnson', 'bob.johnson@example.com', '+91-98200-00102', 'PLATINUM'),
    ('CUST-601', 'John Doe', 'john.doe@example.com', '+91-98110-00199', 'VIP'),
    ('CUST-FAIL-STRIPE-01', 'Stripe Outage Demo', 'stripe.outage@example.com', '+91-98400-00103', 'STANDARD'),
    ('FRAUD-BLACK-LIST-01', 'Fraud Rejection Demo', 'fraud.rejection@example.com', '+91-98300-00104', 'STANDARD'),
    ('ADM-001', 'Operations Admin', 'admin@example.com', '+91-80-4000-0001', 'STANDARD'),
    ('DEL-001', 'Dev Driver One', 'driver1@example.com', '+91-98450-00011', 'STANDARD'),
    ('DEL-002', 'Dev Driver Two', 'driver2@example.com', '+91-98450-00012', 'STANDARD'),
    ('SVC-WAREHOUSE', 'Warehouse System', 'warehouse@example.com', NULL, 'STANDARD')
ON CONFLICT (user_id) DO UPDATE
    SET full_name = EXCLUDED.full_name,
        email     = EXCLUDED.email,
        phone     = EXCLUDED.phone,
        tier      = EXCLUDED.tier
    -- Also moves the earlier US demo phone numbers to Indian ones
    WHERE app_users.email IS NULL OR app_users.phone LIKE '+1-555-%';

INSERT INTO user_roles (user_id, role) VALUES
    ('CUST-100', 'CUSTOMER'),
    ('CUST-101', 'CUSTOMER'),
    ('CUST-601', 'CUSTOMER'),
    ('CUST-FAIL-STRIPE-01', 'CUSTOMER'),
    ('FRAUD-BLACK-LIST-01', 'CUSTOMER'),
    ('ADM-001', 'ADMIN'),
    ('DEL-001', 'DELIVERY'),
    ('DEL-002', 'DELIVERY'),
    ('SVC-WAREHOUSE', 'SERVICE')
ON CONFLICT DO NOTHING;

UPDATE app_users u SET keycloak_subject = k.subject
FROM (VALUES
    ('CUST-100', '11111111-0000-4000-8000-000000000100'),
    ('CUST-101', '11111111-0000-4000-8000-000000000101'),
    ('CUST-601', '11111111-0000-4000-8000-000000000601'),
    ('ADM-001', '22222222-0000-4000-8000-000000000001'),
    ('DEL-001', '33333333-0000-4000-8000-000000000001'),
    ('DEL-002', '33333333-0000-4000-8000-000000000002'),
    ('SVC-WAREHOUSE', '44444444-0000-4000-8000-000000000001')
) AS k (user_id, subject)
WHERE u.user_id = k.user_id AND u.keycloak_subject IS NULL;

INSERT INTO customer_addresses
    (user_id, label, recipient_name, recipient_phone, address_line1, city, state_region, postal_code, country, is_default)
VALUES
    -- Karnataka is the seller's state: CGST + SGST. The others pay IGST.
    ('CUST-100', 'Home', 'Alice Smith', '+91-98450-00101', '12, 4th Cross, Indiranagar', 'Bengaluru', 'Karnataka', '560038', 'IN', TRUE),
    ('CUST-101', 'Home', 'Bob Johnson', '+91-98200-00102', '48, Marine Drive', 'Mumbai', 'Maharashtra', '400002', 'IN', TRUE),
    ('CUST-601', 'Office', 'John Doe', '+91-98110-00199', '9, Connaught Place', 'New Delhi', 'Delhi', '110001', 'IN', TRUE),
    ('CUST-FAIL-STRIPE-01', 'Home', 'Stripe Outage Demo', '+91-98400-00103', '1, Anna Salai', 'Chennai', 'Tamil Nadu', '600002', 'IN', TRUE),
    ('FRAUD-BLACK-LIST-01', 'Home', 'Fraud Rejection Demo', '+91-98300-00104', '2, Park Street', 'Kolkata', 'West Bengal', '700016', 'IN', TRUE)
-- Earlier demo addresses were in the US, where the platform no longer delivers
ON CONFLICT (user_id) WHERE is_default DO UPDATE
    SET recipient_name  = EXCLUDED.recipient_name,
        recipient_phone = EXCLUDED.recipient_phone,
        address_line1   = EXCLUDED.address_line1,
        city            = EXCLUDED.city,
        state_region    = EXCLUDED.state_region,
        postal_code     = EXCLUDED.postal_code,
        country         = EXCLUDED.country
    WHERE customer_addresses.country = 'US';
