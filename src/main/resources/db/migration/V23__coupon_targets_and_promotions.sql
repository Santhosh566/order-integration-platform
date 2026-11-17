-- Coupons for chosen products or categories, and promotions (ads) in the shop.

-- ── Coupon targets ─────────────────────────────────────────────────────────
-- A coupon with no rows here is for the whole order. With rows, it takes money off only the lines
-- whose product is listed, or is in a listed category (a main category includes its sub-categories),
-- and its minimum order counts only those lines.

CREATE TABLE coupon_targets (
    target_id    BIGSERIAL    PRIMARY KEY,
    coupon_code  VARCHAR(30)  NOT NULL REFERENCES coupons (code) ON DELETE CASCADE,
    sku          VARCHAR(64)  REFERENCES inventory (sku),
    category_id  BIGINT       REFERENCES categories (category_id),
    CHECK ((sku IS NULL) <> (category_id IS NULL))
);

CREATE UNIQUE INDEX coupon_targets_sku_once ON coupon_targets (coupon_code, sku) WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX coupon_targets_category_once ON coupon_targets (coupon_code, category_id) WHERE category_id IS NOT NULL;
CREATE INDEX coupon_targets_by_sku ON coupon_targets (sku) WHERE sku IS NOT NULL;
CREATE INDEX coupon_targets_by_category ON coupon_targets (category_id) WHERE category_id IS NOT NULL;

-- ── Promotions ─────────────────────────────────────────────────────────────
-- A banner at the top of the shop that opens a product, a category, or the products a coupon is for.
-- It shows while it is on, inside its dates, and while what it points at can still be bought
-- (the product on sale, the category shown, the coupon live and not used up).

CREATE TABLE promotions (
    promotion_id   BIGSERIAL     PRIMARY KEY,
    title          VARCHAR(80)   NOT NULL,
    message        VARCHAR(200),
    -- The button's words, e.g. "Shop now"
    call_to_action VARCHAR(30),
    -- The background of a banner without a picture, and the button colour
    theme          VARCHAR(10)   NOT NULL DEFAULT 'BLUE'
                   CHECK (theme IN ('BLUE', 'GREEN', 'ORANGE', 'GRAPE', 'RED', 'DARK')),
    -- A wide picture (3:1) in the photo bucket, served at /api/public/images/{key}
    image_key      VARCHAR(255)  UNIQUE,
    image_alt      VARCHAR(200),
    target_type    VARCHAR(10)   NOT NULL CHECK (target_type IN ('PRODUCT', 'CATEGORY', 'COUPON')),
    sku            VARCHAR(64)   REFERENCES inventory (sku),
    category_id    BIGINT        REFERENCES categories (category_id),
    coupon_code    VARCHAR(30)   REFERENCES coupons (code),
    starts_at      TIMESTAMP(6),
    ends_at        TIMESTAMP(6),
    -- Higher first
    priority       INTEGER       NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 100),
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Counted from signed-in shoppers: the banner came into view, and was opened
    impressions    BIGINT        NOT NULL DEFAULT 0,
    clicks         BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6)  NOT NULL DEFAULT now(),
    created_by     VARCHAR(255),
    updated_at     TIMESTAMP(6)  NOT NULL DEFAULT now(),
    updated_by     VARCHAR(255),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    -- Exactly the field the target type needs
    CHECK (target_type <> 'PRODUCT'  OR (sku IS NOT NULL AND category_id IS NULL AND coupon_code IS NULL)),
    CHECK (target_type <> 'CATEGORY' OR (category_id IS NOT NULL AND sku IS NULL AND coupon_code IS NULL)),
    CHECK (target_type <> 'COUPON'   OR (coupon_code IS NOT NULL AND sku IS NULL AND category_id IS NULL))
);

CREATE INDEX promotions_live ON promotions (priority DESC, created_at DESC) WHERE active;
