-- Customers, delivery staff and admins. user_id keeps the existing CUST-* format, so orders link up unchanged.
-- email and password_hash stay empty for imported customers until they register.
CREATE TABLE app_users (
    user_id       VARCHAR(255) PRIMARY KEY,
    full_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(255),
    phone         VARCHAR(32),
    password_hash VARCHAR(255),
    tier          VARCHAR(32)  NOT NULL DEFAULT 'STANDARD',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP(6) NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX app_users_email_unique ON app_users (lower(email));

CREATE TABLE user_roles (
    user_id VARCHAR(255) NOT NULL REFERENCES app_users (user_id) ON DELETE CASCADE,
    role    VARCHAR(32)  NOT NULL CHECK (role IN ('ADMIN', 'CUSTOMER', 'DELIVERY')),
    PRIMARY KEY (user_id, role)
);

CREATE TABLE customer_addresses (
    address_id      BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL REFERENCES app_users (user_id) ON DELETE CASCADE,
    label           VARCHAR(64),
    recipient_name  VARCHAR(255) NOT NULL,
    recipient_phone VARCHAR(32)  NOT NULL,
    address_line1   VARCHAR(255) NOT NULL,
    address_line2   VARCHAR(255),
    city            VARCHAR(255) NOT NULL,
    state_region    VARCHAR(255),
    postal_code     VARCHAR(255) NOT NULL,
    country         VARCHAR(255) NOT NULL,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT now()
);

CREATE INDEX customer_addresses_user ON customer_addresses (user_id);
CREATE UNIQUE INDEX customer_addresses_one_default ON customer_addresses (user_id) WHERE is_default;

-- Copied onto the order, so a later change to a saved address cannot alter past orders.
-- Stays empty for orders placed before addresses existed.
ALTER TABLE orders ADD COLUMN recipient_name  VARCHAR(255);
ALTER TABLE orders ADD COLUMN recipient_phone VARCHAR(32);
ALTER TABLE orders ADD COLUMN address_line1   VARCHAR(255);
ALTER TABLE orders ADD COLUMN address_line2   VARCHAR(255);
ALTER TABLE orders ADD COLUMN city            VARCHAR(255);
ALTER TABLE orders ADD COLUMN state_region    VARCHAR(255);
ALTER TABLE orders ADD COLUMN postal_code     VARCHAR(255);
ALTER TABLE orders ADD COLUMN country         VARCHAR(255);

-- One row per delivery attempt
CREATE TABLE delivery_assignments (
    assignment_id    BIGSERIAL PRIMARY KEY,
    order_id         VARCHAR(255) NOT NULL REFERENCES orders (order_id),
    delivery_user_id VARCHAR(255) NOT NULL REFERENCES app_users (user_id),
    status           VARCHAR(32)  NOT NULL
                     CHECK (status IN ('ASSIGNED', 'PICKED_UP', 'DELIVERED', 'FAILED', 'REASSIGNED')),
    attempt_number   INTEGER      NOT NULL DEFAULT 1,
    assigned_at      TIMESTAMP(6) NOT NULL DEFAULT now(),
    picked_up_at     TIMESTAMP(6),
    delivered_at     TIMESTAMP(6),
    failed_at        TIMESTAMP(6),
    failure_reason   VARCHAR(255),
    UNIQUE (order_id, attempt_number)
);

-- Two delivery users can never hold the same order, even if application checks race
CREATE UNIQUE INDEX delivery_assignments_one_live ON delivery_assignments (order_id)
    WHERE status IN ('ASSIGNED', 'PICKED_UP');

-- Every customer id already on an order becomes a user. Their contact details were never recorded.
INSERT INTO app_users (user_id, full_name, tier)
SELECT DISTINCT customer_id,
                replace(customer_id, '-', ' '),
                CASE WHEN upper(customer_id) LIKE '%VIP%' THEN 'VIP' ELSE 'STANDARD' END
FROM orders;

INSERT INTO user_roles (user_id, role)
SELECT user_id, 'CUSTOMER' FROM app_users;

ALTER TABLE orders ADD CONSTRAINT orders_customer_fk
    FOREIGN KEY (customer_id) REFERENCES app_users (user_id);
