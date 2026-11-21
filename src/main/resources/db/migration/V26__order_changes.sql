-- Changes to an order after it was placed: a new delivery address, another delivery day, or more items.
-- Each change is a row here, so the customer and support can see who changed what, and when. The order
-- itself holds only what it is now.

CREATE TABLE order_changes (
    change_id    BIGSERIAL      PRIMARY KEY,
    order_id     VARCHAR(255)   NOT NULL REFERENCES orders (order_id),
    kind         VARCHAR(16)    NOT NULL CHECK (kind IN ('ADDRESS', 'DELIVERY_SLOT', 'ITEMS_ADDED')),
    -- What it was and what it is now, as the customer reads it ("Standard delivery", "Fri 21 Nov, 9 AM – 1 PM",
    -- an address on one line, "2 × Gel pen")
    before_value VARCHAR(1000),
    after_value  VARCHAR(1000)  NOT NULL,
    -- ITEMS_ADDED: what the added items cost, GST included, and how it was paid
    amount       NUMERIC(12, 2) CHECK (amount IS NULL OR amount > 0),
    note         VARCHAR(500),
    changed_by   VARCHAR(255)   NOT NULL,
    -- Made by support on the customer's behalf
    by_staff     BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6)   NOT NULL DEFAULT now(),
    CHECK ((kind = 'ITEMS_ADDED') = (amount IS NOT NULL))
);

CREATE INDEX order_changes_by_order ON order_changes (order_id, created_at);

-- Lines added after the order was placed. They are priced at the catalogue price of the day they were
-- added, without the order's coupon.
ALTER TABLE order_items ADD COLUMN added_at TIMESTAMP(6);
