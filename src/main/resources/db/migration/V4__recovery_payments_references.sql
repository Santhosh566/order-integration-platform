-- When the order last changed status, so recovery can tell a stuck order from a busy one
ALTER TABLE orders ADD COLUMN status_changed_at TIMESTAMP(6);
UPDATE orders SET status_changed_at = coalesce(created_at, now());
ALTER TABLE orders ALTER COLUMN status_changed_at SET NOT NULL;

-- How often recovery has picked the order up, and when it last did
ALTER TABLE orders ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN last_recovery_at TIMESTAMP(6);

CREATE INDEX orders_in_flight ON orders (status_changed_at)
    WHERE status IN ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMED');

-- The caller's own id for the order. order_id is always issued by the server.
ALTER TABLE orders ADD COLUMN external_reference VARCHAR(255);
CREATE UNIQUE INDEX orders_customer_reference ON orders (customer_id, external_reference)
    WHERE external_reference IS NOT NULL;

-- Every charge and refund. The order only shows its latest attempt; this keeps them all.
CREATE TABLE payment_transactions (
    payment_id     BIGSERIAL PRIMARY KEY,
    order_id       VARCHAR(255)  NOT NULL REFERENCES orders (order_id),
    kind           VARCHAR(16)   NOT NULL CHECK (kind IN ('CHARGE', 'REFUND')),
    gateway        VARCHAR(32),
    transaction_id VARCHAR(255)  NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL,
    created_at     TIMESTAMP(6)  NOT NULL DEFAULT now()
);

CREATE INDEX payment_transactions_order ON payment_transactions (order_id);

INSERT INTO payment_transactions (order_id, kind, gateway, transaction_id, amount, created_at)
SELECT order_id, 'CHARGE', payment_gateway, payment_transaction_id, total, coalesce(created_at, now())
FROM orders
WHERE payment_transaction_id IS NOT NULL;

INSERT INTO payment_transactions (order_id, kind, gateway, transaction_id, amount, created_at)
SELECT order_id, 'REFUND', payment_gateway, 'RFD-' || payment_transaction_id, total, refunded_at
FROM orders
WHERE payment_transaction_id IS NOT NULL AND refunded_at IS NOT NULL;
