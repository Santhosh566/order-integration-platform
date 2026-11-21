-- The customer's cart, kept on the server so it follows them between devices, and the reminder emails
-- for carts left without an order ("abandoned carts").

-- One cart from the first item put in until it is ordered, emptied or left too long. A customer has at
-- most one ACTIVE cart; the closed ones stay for the recovery figures.
CREATE TABLE carts (
    cart_id            BIGSERIAL     PRIMARY KEY,
    customer_id        VARCHAR(255)  NOT NULL REFERENCES app_users (user_id),
    status             VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE'
                       CHECK (status IN ('ACTIVE', 'ORDERED', 'RECOVERED', 'EMPTIED', 'EXPIRED')),
    created_at         TIMESTAMP(6)  NOT NULL DEFAULT now(),
    -- The customer last changed what is in it; reminders are timed from here
    updated_at         TIMESTAMP(6)  NOT NULL DEFAULT now(),
    reminders_sent     INTEGER       NOT NULL DEFAULT 0 CHECK (reminders_sent >= 0),
    last_reminded_at   TIMESTAMP(6),
    -- The reminder job last looked at it (sent, or found nothing that can be bought); it looks again an hour later
    checked_at         TIMESTAMP(6),
    -- The personal coupon sent with a reminder, if any
    reminder_coupon    VARCHAR(30),
    closed_at          TIMESTAMP(6),
    -- ORDERED / RECOVERED: the order that took its items. RECOVERED means it came after a reminder.
    order_id           VARCHAR(255)  REFERENCES orders (order_id),
    CHECK ((status = 'ACTIVE') = (closed_at IS NULL)),
    CHECK (status NOT IN ('ORDERED', 'RECOVERED') OR order_id IS NOT NULL)
);

CREATE UNIQUE INDEX carts_one_active ON carts (customer_id) WHERE status = 'ACTIVE';
-- The reminder job's search
CREATE INDEX carts_due ON carts (updated_at) WHERE status = 'ACTIVE';
-- The admin's figures over a period
CREATE INDEX carts_created ON carts (created_at);

CREATE TABLE cart_items (
    cart_id    BIGINT        NOT NULL REFERENCES carts (cart_id) ON DELETE CASCADE,
    sku        VARCHAR(64)   NOT NULL REFERENCES inventory (sku),
    quantity   INTEGER       NOT NULL CHECK (quantity BETWEEN 1 AND 20),
    -- The price (GST included) when it was put in, so the reminder can tell the customer it is cheaper now
    price_seen NUMERIC(12, 2) NOT NULL CHECK (price_seen >= 0),
    added_at   TIMESTAMP(6)  NOT NULL DEFAULT now(),
    PRIMARY KEY (cart_id, sku)
);

-- A coupon for one customer only (the cart reminder's "5% off your cart"); null for everyone.
-- Personal coupons are kept off the shop's offers, promotions and the admin's coupon list.
ALTER TABLE coupons ADD COLUMN customer_id VARCHAR(255) REFERENCES app_users (user_id);
CREATE INDEX coupons_personal ON coupons (customer_id) WHERE customer_id IS NOT NULL;

-- The customer said no to cart reminders and offers by email. Order emails still go out.
ALTER TABLE user_profiles ADD COLUMN cart_reminders BOOLEAN NOT NULL DEFAULT TRUE;
