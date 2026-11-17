-- Customer returns and partial refunds. Every refund is backed by a GST credit note whose lines
-- say which order line it reduces and by how much tax, so refunds never exceed what was charged
-- and the tax given back matches the tax collected.

-- ── Orders ───────────────────────────────────────────────────────────────────
ALTER TABLE orders
    -- Money given back so far; refunded_at is set once it reaches the total
    ADD COLUMN refunded_total NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (refunded_total >= 0),
    -- The return window counts from here
    ADD COLUMN delivered_at   TIMESTAMP(6),
    ADD COLUMN return_status  VARCHAR(10) NOT NULL DEFAULT 'NONE' CHECK (return_status IN ('NONE', 'PARTIAL', 'FULL'));

-- Refunds before this release were always for the whole order
UPDATE orders SET refunded_total = total WHERE refunded_at IS NOT NULL;
UPDATE orders SET delivered_at = status_changed_at WHERE status = 'DELIVERED';

ALTER TABLE orders ADD CONSTRAINT orders_refund_within_total CHECK (refunded_total <= total);

-- ── Return requests ────────────────────────────────────────────────────────────
CREATE TABLE return_requests (
    return_id     VARCHAR(40)  PRIMARY KEY,
    order_id      VARCHAR(255) NOT NULL REFERENCES orders (order_id),
    customer_id   VARCHAR(255) NOT NULL,
    status        VARCHAR(12)  NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'REFUNDED')),
    reason        VARCHAR(500) NOT NULL,
    requested_at  TIMESTAMP(6) NOT NULL,
    decided_at    TIMESTAMP(6),
    decided_by    VARCHAR(255),
    decision_note VARCHAR(500),
    received_at   TIMESTAMP(6),
    received_by   VARCHAR(255)
);

CREATE INDEX return_requests_order ON return_requests (order_id);
CREATE INDEX return_requests_open ON return_requests (status) WHERE status IN ('REQUESTED', 'APPROVED');

CREATE TABLE return_items (
    id            BIGSERIAL    PRIMARY KEY,
    return_id     VARCHAR(40)  NOT NULL REFERENCES return_requests (return_id),
    order_item_id BIGINT       NOT NULL REFERENCES order_items (id),
    sku           VARCHAR(255) NOT NULL,
    quantity      INTEGER      NOT NULL CHECK (quantity > 0),
    -- Set when the warehouse inspects it: SELLABLE goes back on the shelf, DAMAGED does not
    item_condition VARCHAR(10) CHECK (item_condition IN ('SELLABLE', 'DAMAGED'))
);

CREATE INDEX return_items_return ON return_items (return_id);

-- ── Credit notes: the GST document for money given back ───────────────────────
CREATE TABLE credit_notes (
    credit_note_number VARCHAR(16)   PRIMARY KEY,
    order_id           VARCHAR(255)  NOT NULL REFERENCES orders (order_id),
    -- Set for a return; empty for a goodwill refund
    return_id          VARCHAR(40)   REFERENCES return_requests (return_id),
    kind               VARCHAR(10)   NOT NULL CHECK (kind IN ('RETURN', 'GOODWILL')),
    currency           VARCHAR(3)    NOT NULL,
    taxable_value      NUMERIC(12, 2) NOT NULL,
    cgst_amount        NUMERIC(12, 2) NOT NULL,
    sgst_amount        NUMERIC(12, 2) NOT NULL,
    igst_amount        NUMERIC(12, 2) NOT NULL,
    total              NUMERIC(12, 2) NOT NULL CHECK (total > 0),
    reason             VARCHAR(500),
    payment_id         BIGINT        NOT NULL REFERENCES payment_transactions (payment_id),
    issued_by          VARCHAR(255)  NOT NULL,
    issued_at          TIMESTAMP(6)  NOT NULL,
    CHECK (total = taxable_value + cgst_amount + sgst_amount + igst_amount)
);

CREATE INDEX credit_notes_order ON credit_notes (order_id);
CREATE UNIQUE INDEX credit_notes_one_per_return ON credit_notes (return_id) WHERE return_id IS NOT NULL;

CREATE TABLE credit_note_lines (
    id                 BIGSERIAL     PRIMARY KEY,
    credit_note_number VARCHAR(16)   NOT NULL REFERENCES credit_notes (credit_note_number),
    order_item_id      BIGINT        NOT NULL REFERENCES order_items (id),
    -- Units given back; 0 for a goodwill refund, which only lowers the price
    quantity           INTEGER       NOT NULL CHECK (quantity >= 0),
    taxable_value      NUMERIC(12, 2) NOT NULL CHECK (taxable_value >= 0),
    cgst_amount        NUMERIC(12, 2) NOT NULL CHECK (cgst_amount >= 0),
    sgst_amount        NUMERIC(12, 2) NOT NULL CHECK (sgst_amount >= 0),
    igst_amount        NUMERIC(12, 2) NOT NULL CHECK (igst_amount >= 0),
    CHECK (cgst_amount = sgst_amount)
);

CREATE INDEX credit_note_lines_item ON credit_note_lines (order_item_id);
