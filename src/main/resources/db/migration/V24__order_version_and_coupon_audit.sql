-- Optimistic locking: every change to an order row raises its version, so a person who acts on an
-- order they opened earlier can be told it changed in the meantime (If-Match → 412).
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Who last changed a coupon (its products, end date or on/off), shown on the admin's list
ALTER TABLE coupons ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE coupons ADD COLUMN updated_by VARCHAR(64);
