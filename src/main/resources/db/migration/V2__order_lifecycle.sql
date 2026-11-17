-- Payment record: needed to know whether a refund is owed
ALTER TABLE orders ADD COLUMN payment_transaction_id VARCHAR(255);
ALTER TABLE orders ADD COLUMN payment_gateway VARCHAR(255);
ALTER TABLE orders ADD COLUMN refunded_at TIMESTAMP(6);

-- Stable invoice identity
ALTER TABLE orders ADD COLUMN invoice_number VARCHAR(255);
ALTER TABLE orders ADD COLUMN invoice_date DATE;
CREATE SEQUENCE invoice_number_seq;

-- Soft delete: financial records stay in the table
ALTER TABLE orders ADD COLUMN deleted_at TIMESTAMP(6);

-- Lets outbox recovery measure how long a row has been claimed
ALTER TABLE outbox_events ADD COLUMN claimed_at TIMESTAMP(6);

-- Last line of defence against overselling
ALTER TABLE inventory ADD CONSTRAINT inventory_quantities_not_negative
    CHECK (available_quantity >= 0 AND reserved_quantity >= 0);

-- Durable state for the fulfillment aggregator, in the layout Camel's JdbcAggregationRepository expects
CREATE TABLE fulfillment_aggregation (
    id       VARCHAR(255) PRIMARY KEY,
    exchange BYTEA NOT NULL,
    version  BIGINT NOT NULL
);

CREATE TABLE fulfillment_aggregation_completed (
    id       VARCHAR(255) PRIMARY KEY,
    exchange BYTEA NOT NULL,
    version  BIGINT NOT NULL
);
