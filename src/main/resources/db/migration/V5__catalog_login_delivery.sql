-- The catalogue price: orders are charged this, never a price the caller sends
ALTER TABLE inventory ADD COLUMN product_name VARCHAR(255);
ALTER TABLE inventory ADD COLUMN unit_price NUMERIC(12, 2) CHECK (unit_price >= 0);

UPDATE inventory SET product_name = 'Ergonomic Office Chair', unit_price = 45.00 WHERE sku = 'WIDGET-A';
UPDATE inventory SET product_name = 'Mechanical Desk Keyboard', unit_price = 30.00 WHERE sku = 'WIDGET-B';
UPDATE inventory SET product_name = 'Dual 4K Monitor Arm', unit_price = 150.00 WHERE sku = 'GADGET-X';
UPDATE inventory SET product_name = 'Discontinued Item', unit_price = 10.00 WHERE sku = 'OUT-OF-STOCK';

-- On a new database the app seeds the catalogue itself; this only fills an existing one
INSERT INTO inventory (sku, available_quantity, reserved_quantity, product_name, unit_price)
SELECT 'SERVER-PRO', 5, 0, 'Enterprise Server Rack', 1200.00
WHERE EXISTS (SELECT 1 FROM inventory)
ON CONFLICT (sku) DO NOTHING;

-- Keycloak signs users in and keeps their passwords; each user here is linked to their Keycloak user id
ALTER TABLE app_users ADD COLUMN keycloak_subject VARCHAR(255);
CREATE UNIQUE INDEX app_users_keycloak_subject ON app_users (keycloak_subject) WHERE keycloak_subject IS NOT NULL;
ALTER TABLE app_users DROP COLUMN password_hash;

-- Service accounts for systems that call in, such as the warehouse and payment webhooks
ALTER TABLE user_roles DROP CONSTRAINT user_roles_role_check;
ALTER TABLE user_roles ADD CONSTRAINT user_roles_role_check
    CHECK (role IN ('ADMIN', 'CUSTOMER', 'DELIVERY', 'SERVICE'));

-- A delivery is confirmed with a one-time code the customer receives; only its hash is kept
ALTER TABLE delivery_assignments ADD COLUMN delivery_code_hash VARCHAR(255);
ALTER TABLE delivery_assignments ADD COLUMN code_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE delivery_assignments DROP CONSTRAINT delivery_assignments_status_check;
ALTER TABLE delivery_assignments ADD CONSTRAINT delivery_assignments_status_check
    CHECK (status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERED', 'FAILED', 'REASSIGNED', 'CANCELLED'));
CREATE INDEX delivery_assignments_user ON delivery_assignments (delivery_user_id);

-- Recovery also watches orders waiting at SHIPPED for warehouse signals
DROP INDEX orders_in_flight;
CREATE INDEX orders_in_flight ON orders (status_changed_at)
    WHERE status IN ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMED', 'SHIPPED');
