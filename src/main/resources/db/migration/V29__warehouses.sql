-- Multi-warehouse: stock is kept per warehouse, and each warehouse has its own GST registration.
--
-- Until now the shop had one stock pool per product and one seller registration in Karnataka. Here that pool
-- becomes the first warehouse, Bengaluru, holding all existing stock, and every past movement and order belongs to
-- it, so nothing behaves differently yet. inventory.available_quantity and reserved_quantity stay, as the totals
-- over all warehouses: the shop's pages read them, and StockLedger keeps them equal to the sum, in the same
-- transaction as each warehouse's change.

CREATE TABLE warehouses (
    warehouse_id  BIGSERIAL     PRIMARY KEY,
    -- Short code people use: BLR, GGN, CCU, BHI
    code          VARCHAR(10)   NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{2,10}$'),
    name          VARCHAR(100)  NOT NULL,
    address       VARCHAR(300)  NOT NULL,
    pin           CHAR(6)       NOT NULL CHECK (pin ~ '^[1-9][0-9]{5}$'),
    -- GST state code (IndianState): decides CGST + SGST or IGST for what it ships
    state_code    CHAR(2)       NOT NULL,
    -- Each state where we hold stock has its own GST registration, and its own invoice series
    gstin         CHAR(15)      NOT NULL CHECK (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]$'),
    -- Where it is, for "the nearest warehouse that has the stock" (PostGIS)
    location      geography(Point, 4326) NOT NULL,
    -- Each GSTIN numbers its own invoices and credit notes ("INV/26-27/000021"). Numbers are at most 16 characters
    -- (CGST Rule 46), so prefixes are 2 or 3 capitals, different for every warehouse
    invoice_prefix     VARCHAR(3) NOT NULL UNIQUE CHECK (invoice_prefix ~ '^[A-Z]{2,3}$'),
    credit_note_prefix VARCHAR(3) NOT NULL UNIQUE CHECK (credit_note_prefix ~ '^[A-Z]{2,3}$'),
    -- Orders packed by this time leave the same day (India time)
    cutoff_time   TIME          NOT NULL DEFAULT '14:00',
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    -- The warehouse stock goes to when nobody says which (opening stock, phase 1 orders)
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP(6)  NOT NULL DEFAULT now(),
    CHECK (substr(gstin, 1, 2) = state_code),
    CHECK (invoice_prefix <> credit_note_prefix)
);

CREATE UNIQUE INDEX warehouses_one_default ON warehouses (is_default) WHERE is_default;
CREATE INDEX warehouses_location ON warehouses USING gist (location);

-- The seller registration the shop has used so far, as its first warehouse; its invoices keep their series
INSERT INTO warehouses (code, name, address, pin, state_code, gstin, location, is_default, invoice_prefix, credit_note_prefix)
VALUES ('BLR', 'Bengaluru fulfilment centre', '4th Floor, 80 Feet Road, Koramangala, Bengaluru, Karnataka', '560034',
        '29', '29AABCO1234F1Z5', ST_SetSRID(ST_MakePoint(77.6245, 12.9352), 4326)::geography, TRUE, 'INV', 'CN');

-- Each product's stock in each warehouse; a missing row means none there
CREATE TABLE warehouse_stock (
    warehouse_id       BIGINT      NOT NULL REFERENCES warehouses (warehouse_id),
    sku                VARCHAR(64) NOT NULL REFERENCES inventory (sku),
    available_quantity INTEGER     NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    reserved_quantity  INTEGER     NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    -- The low-stock alert for this warehouse; 0 for none
    reorder_level      INTEGER     NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    PRIMARY KEY (warehouse_id, sku)
);

CREATE INDEX warehouse_stock_by_sku ON warehouse_stock (sku);

INSERT INTO warehouse_stock (warehouse_id, sku, available_quantity, reserved_quantity, reorder_level)
SELECT w.warehouse_id, i.sku, greatest(i.available_quantity, 0), greatest(i.reserved_quantity, 0), i.reorder_level
FROM inventory i CROSS JOIN warehouses w WHERE w.is_default;

-- The ledger is per warehouse: available_after and reserved_after are that warehouse's balances
ALTER TABLE stock_movements ADD COLUMN warehouse_id BIGINT REFERENCES warehouses (warehouse_id);
UPDATE stock_movements SET warehouse_id = (SELECT warehouse_id FROM warehouses WHERE is_default);
ALTER TABLE stock_movements ALTER COLUMN warehouse_id SET NOT NULL;
CREATE INDEX stock_movements_warehouse ON stock_movements (warehouse_id, sku, id);

-- Moving stock between warehouses (phase 4)
ALTER TABLE stock_movements DROP CONSTRAINT stock_movements_movement_type_check;
ALTER TABLE stock_movements ADD CONSTRAINT stock_movements_movement_type_check CHECK (movement_type IN
    ('OPENING', 'RECEIPT', 'ADJUSTMENT', 'WRITE_OFF', 'RESERVE', 'RELEASE', 'SHIP', 'RETURN', 'TRANSFER_OUT', 'TRANSFER_IN'));

-- The warehouse an order is packed and shipped from, set when its stock is reserved. Orders so far: Bengaluru.
ALTER TABLE orders ADD COLUMN warehouse_id BIGINT REFERENCES warehouses (warehouse_id);
UPDATE orders SET warehouse_id = (SELECT warehouse_id FROM warehouses WHERE is_default);
CREATE INDEX orders_warehouse ON orders (warehouse_id, status);

-- Drivers work from one warehouse: they see and take only its deliveries and return pickups. Today's drivers all
-- work from the default one.
ALTER TABLE app_users ADD COLUMN home_warehouse_id BIGINT REFERENCES warehouses (warehouse_id);
UPDATE app_users u SET home_warehouse_id = (SELECT warehouse_id FROM warehouses WHERE is_default)
WHERE EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.user_id AND r.role = 'DELIVERY');
