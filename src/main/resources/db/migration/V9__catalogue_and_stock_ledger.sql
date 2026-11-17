-- Catalogue administration: products can be retired, have a reorder level, and are edited
-- with a version check. Every stock change is a row in stock_movements, and every price a row
-- in product_prices, so each number has a reason, a time and a person behind it.

-- ── Products ─────────────────────────────────────────────────────────────────
ALTER TABLE inventory
    -- A retired product cannot be ordered; old orders and invoices still show it
    ADD COLUMN active          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Staff are emailed when available stock falls to this level; 0 turns the alert off
    ADD COLUMN reorder_level   INTEGER      NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    -- Raised by every catalogue edit; an edit based on an older version is refused
    ADD COLUMN catalog_version INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN updated_at      TIMESTAMP(6),
    ADD COLUMN updated_by      VARCHAR(255);

-- Stock can never go below zero: inventory_quantities_not_negative (V2)

-- ── Price history ────────────────────────────────────────────────────────────
-- A row takes effect at effective_from. applied_at is set when it became the catalogue price;
-- a future row waits for the scheduler, and can be cancelled until then.
CREATE TABLE product_prices (
    id             BIGSERIAL     PRIMARY KEY,
    sku            VARCHAR(64)   NOT NULL REFERENCES inventory (sku),
    unit_price     NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    gst_rate       NUMERIC(5, 2) NOT NULL CHECK (gst_rate IN (0, 0.25, 3, 5, 12, 18, 28, 40)),
    effective_from TIMESTAMP(6)  NOT NULL,
    applied_at     TIMESTAMP(6),
    cancelled_at   TIMESTAMP(6),
    reason         VARCHAR(500),
    created_by     VARCHAR(255)  NOT NULL,
    created_at     TIMESTAMP(6)  NOT NULL,
    CHECK (applied_at IS NULL OR cancelled_at IS NULL)
);

CREATE INDEX product_prices_sku ON product_prices (sku, effective_from);
CREATE INDEX product_prices_due ON product_prices (effective_from) WHERE applied_at IS NULL AND cancelled_at IS NULL;

-- Today's prices are the first entry of each history
INSERT INTO product_prices (sku, unit_price, gst_rate, effective_from, applied_at, reason, created_by, created_at)
SELECT sku, unit_price, gst_rate, now(), now(), 'Price before price history was kept', 'migration', now()
FROM inventory
WHERE unit_price IS NOT NULL;

-- ── Stock ledger ─────────────────────────────────────────────────────────────
-- Rows are only ever added. The *_after columns are the balances right after the change.
CREATE TABLE stock_movements (
    id               BIGSERIAL    PRIMARY KEY,
    sku              VARCHAR(64)  NOT NULL REFERENCES inventory (sku),
    movement_type    VARCHAR(20)  NOT NULL CHECK (movement_type IN
                         ('OPENING', 'RECEIPT', 'ADJUSTMENT', 'WRITE_OFF', 'RESERVE', 'RELEASE', 'SHIP', 'RETURN')),
    available_change INTEGER      NOT NULL,
    reserved_change  INTEGER      NOT NULL,
    available_after  INTEGER      NOT NULL CHECK (available_after >= 0),
    reserved_after   INTEGER      NOT NULL CHECK (reserved_after >= 0),
    order_id         VARCHAR(255),
    reason           VARCHAR(500),
    created_by       VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CHECK (available_change <> 0 OR reserved_change <> 0 OR movement_type = 'OPENING')
);

CREATE INDEX stock_movements_sku ON stock_movements (sku, id);
CREATE INDEX stock_movements_order ON stock_movements (order_id) WHERE order_id IS NOT NULL;

-- The ledger starts from today's balances
INSERT INTO stock_movements (sku, movement_type, available_change, reserved_change,
                             available_after, reserved_after, reason, created_by, created_at)
SELECT sku, 'OPENING', available_quantity, reserved_quantity,
       greatest(available_quantity, 0), greatest(reserved_quantity, 0),
       'Balance when the stock ledger started', 'migration', now()
FROM inventory;

-- ── Staff messages through the notification outbox ────────────────────────────
-- A message goes either to an order's customer or straight to an address, such as the
-- low-stock alert to the inventory team
ALTER TABLE customer_notifications
    ALTER COLUMN order_id DROP NOT NULL,
    ALTER COLUMN customer_id DROP NOT NULL,
    ADD COLUMN recipient_email VARCHAR(320),
    ADD CONSTRAINT customer_notifications_has_recipient
        CHECK (recipient_email IS NOT NULL OR (order_id IS NOT NULL AND customer_id IS NOT NULL));
