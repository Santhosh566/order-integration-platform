-- Development coupons. Production leaves this folder out through ORDERS_FLYWAY_LOCATIONS.
INSERT INTO coupons (code, description, discount_type, discount_value, max_discount, min_order_value,
                     per_customer_limit, first_order_only, created_by) VALUES
    ('WELCOME10', '10% off your first order, up to ₹500', 'PERCENT', 10, 500, 0, 1, TRUE, 'demo'),
    ('SAVE200', '₹200 off orders of ₹1,999 or more', 'FLAT', 200, NULL, 1999, 3, FALSE, 'demo'),
    ('FESTIVE15', '15% off orders of ₹4,999 or more, up to ₹1,500', 'PERCENT', 15, 1500, 4999, 2, FALSE, 'demo')
ON CONFLICT (code) DO NOTHING;
