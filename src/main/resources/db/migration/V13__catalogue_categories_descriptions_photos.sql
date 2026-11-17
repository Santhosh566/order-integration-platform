-- The shop window: categories (two levels, e.g. Office > Pens), a description per product, and
-- product photos stored in MinIO (bucket order-platform-products); this table keeps their keys.

CREATE TABLE categories (
    category_id BIGSERIAL    PRIMARY KEY,
    -- For addresses such as /shop?category=pens; lower case letters, digits and dashes
    slug        VARCHAR(80)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name        VARCHAR(120) NOT NULL,
    -- A main category has none; a sub-category points to a main one (the service keeps it to two levels)
    parent_id   BIGINT       REFERENCES categories (category_id),
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT now(),
    CHECK (parent_id IS NULL OR parent_id <> category_id)
);

-- No two categories with the same name under one parent
CREATE UNIQUE INDEX categories_name_per_parent ON categories (COALESCE(parent_id, 0), lower(name));

ALTER TABLE inventory ADD COLUMN description TEXT CHECK (length(description) <= 5000);
ALTER TABLE inventory ADD COLUMN category_id BIGINT REFERENCES categories (category_id);
CREATE INDEX inventory_category ON inventory (category_id);

CREATE TABLE product_images (
    image_id      BIGSERIAL    PRIMARY KEY,
    sku           VARCHAR(64)  NOT NULL REFERENCES inventory (sku),
    -- Object keys in the bucket; new keys for every upload, so a cached copy is never wrong
    large_key     VARCHAR(255) NOT NULL UNIQUE,
    thumb_key     VARCHAR(255) NOT NULL UNIQUE,
    -- 0 is the main photo, shown on the product card
    position      INTEGER      NOT NULL CHECK (position >= 0),
    alt_text      VARCHAR(200),
    width         INTEGER      NOT NULL CHECK (width > 0),
    height        INTEGER      NOT NULL CHECK (height > 0),
    size_bytes    INTEGER      NOT NULL CHECK (size_bytes > 0),
    uploaded_by   VARCHAR(255) NOT NULL,
    uploaded_at   TIMESTAMP(6) NOT NULL DEFAULT now()
);

CREATE INDEX product_images_sku ON product_images (sku, position);
