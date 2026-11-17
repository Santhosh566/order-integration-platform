-- Schema as it stood before Flyway. A database that already has these tables
-- is baselined at this version, so this script only runs on an empty database.
CREATE TABLE orders (
    order_id        VARCHAR(255) PRIMARY KEY,
    created_at      TIMESTAMP(6),
    customer_id     VARCHAR(255) NOT NULL,
    order_type      VARCHAR(255),
    status          VARCHAR(255),
    total           DOUBLE PRECISION NOT NULL,
    carrier         VARCHAR(255),
    fraud_score     DOUBLE PRECISION,
    fraud_status    VARCHAR(255),
    shipping_cost   DOUBLE PRECISION,
    tracking_number VARCHAR(255)
);

CREATE TABLE order_items (
    id       BIGSERIAL PRIMARY KEY,
    price    DOUBLE PRECISION NOT NULL,
    quantity INTEGER NOT NULL,
    sku      VARCHAR(255),
    order_id VARCHAR(255) REFERENCES orders (order_id)
);

CREATE TABLE inventory (
    sku                VARCHAR(64) PRIMARY KEY,
    available_quantity INTEGER NOT NULL,
    reserved_quantity  INTEGER NOT NULL
);

CREATE TABLE outbox_events (
    id           VARCHAR(255) PRIMARY KEY,
    created_at   TIMESTAMP(6),
    event_type   VARCHAR(255) NOT NULL,
    order_id     VARCHAR(255) NOT NULL,
    payload      TEXT NOT NULL,
    published_at TIMESTAMP(6),
    status       VARCHAR(255) NOT NULL
);

-- Table used by Camel's JdbcMessageIdRepository (Idempotent Consumer)
CREATE TABLE IF NOT EXISTS CAMEL_MESSAGEPROCESSED (
    processorName VARCHAR(255),
    messageId     VARCHAR(100),
    createdAt     TIMESTAMP,
    PRIMARY KEY (processorName, messageId)
);

CREATE TABLE audit_logs (
    id        BIGSERIAL PRIMARY KEY,
    action    VARCHAR(255) NOT NULL,
    details   VARCHAR(1000),
    order_id  VARCHAR(255) NOT NULL,
    status    VARCHAR(255) NOT NULL,
    timestamp TIMESTAMP(6) NOT NULL
);

CREATE TABLE dead_letter_events (
    id                  BIGSERIAL PRIMARY KEY,
    error_message       TEXT,
    exception_class     VARCHAR(255),
    failed_at           TIMESTAMP(6) NOT NULL,
    order_id            VARCHAR(255),
    payload             TEXT NOT NULL,
    redelivery_attempts INTEGER,
    replayed            BOOLEAN NOT NULL,
    replayed_at         TIMESTAMP(6),
    topic               VARCHAR(255) NOT NULL
);
