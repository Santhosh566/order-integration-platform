-- Checkout extras: coupons, cash on delivery, and a delivery day and time chosen by the customer.

-- ── Coupons ─────────────────────────────────────────────────────────────────

CREATE TABLE coupons (
    -- What the customer types; kept in capitals
    code               VARCHAR(30)   PRIMARY KEY CHECK (code ~ '^[A-Z0-9]{3,30}$'),
    description        VARCHAR(200)  NOT NULL,
    discount_type      VARCHAR(10)   NOT NULL CHECK (discount_type IN ('PERCENT', 'FLAT')),
    -- Percent (at most 90) or rupees
    discount_value     NUMERIC(12, 2) NOT NULL CHECK (discount_value > 0),
    -- The most a percent coupon takes off; null for no cap
    max_discount       NUMERIC(12, 2) CHECK (max_discount > 0),
    -- Cart value (incl. GST, before the discount) needed to use it
    min_order_value    NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (min_order_value >= 0),
    starts_at          TIMESTAMP(6),
    ends_at            TIMESTAMP(6),
    -- Orders that may use it in all; null for no limit. Orders that ended unfulfilled give their use back.
    usage_limit        INTEGER       CHECK (usage_limit > 0),
    per_customer_limit INTEGER       NOT NULL DEFAULT 1 CHECK (per_customer_limit > 0),
    first_order_only   BOOLEAN       NOT NULL DEFAULT FALSE,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP(6)  NOT NULL DEFAULT now(),
    created_by         VARCHAR(255),
    CHECK (discount_type <> 'PERCENT' OR discount_value <= 90),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

-- The coupon an order used, and what it took off. GST is charged on the price after the discount
-- (CGST Act s.15(3)(a): a discount shown on the invoice is not part of the value), so each line keeps
-- its share of the discount, and returns refund what was actually paid.
ALTER TABLE orders ADD COLUMN coupon_code VARCHAR(30) REFERENCES coupons (code);
ALTER TABLE orders ADD COLUMN discount_total NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0);
ALTER TABLE order_items ADD COLUMN discount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (discount >= 0);

CREATE INDEX orders_coupon ON orders (coupon_code, customer_id) WHERE coupon_code IS NOT NULL;

-- ── Payment method ──────────────────────────────────────────────────────────

-- COD: nothing is charged when the order is placed; the driver collects the total at the door
ALTER TABLE orders ADD COLUMN payment_method VARCHAR(10) NOT NULL DEFAULT 'PREPAID'
    CHECK (payment_method IN ('PREPAID', 'COD'));

-- ── Delivery slot ───────────────────────────────────────────────────────────

-- A day and time the customer chose; null for standard delivery (as soon as it arrives)
ALTER TABLE orders ADD COLUMN delivery_date DATE;
ALTER TABLE orders ADD COLUMN delivery_window VARCHAR(10) CHECK (delivery_window IN ('MORNING', 'AFTERNOON', 'EVENING'));
ALTER TABLE orders ADD CONSTRAINT orders_slot_complete CHECK ((delivery_date IS NULL) = (delivery_window IS NULL));

-- Counting a slot's bookings
CREATE INDEX orders_delivery_slot ON orders (delivery_date, delivery_window) WHERE delivery_date IS NOT NULL;
