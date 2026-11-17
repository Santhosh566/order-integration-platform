-- The shop: search, brands and specifications, ratings and reviews, wishlists, PIN codes for
-- delivery estimates, and the GST rule for clothing and footwear (rate set by the price).

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() is only STABLE, so it cannot be used in an index; this wrapper fixes the dictionary
-- and may be (the usual workaround)
CREATE OR REPLACE FUNCTION f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- ── Products ────────────────────────────────────────────────────────────────

ALTER TABLE inventory ADD COLUMN brand VARCHAR(80);
-- Most units of this product one order may hold; null means no limit
ALTER TABLE inventory ADD COLUMN max_per_order INTEGER CHECK (max_per_order > 0);
-- Kept up to date when a review is approved, so the shop can sort and filter by rating quickly
ALTER TABLE inventory ADD COLUMN rating_avg NUMERIC(3, 2) NOT NULL DEFAULT 0 CHECK (rating_avg BETWEEN 0 AND 5);
ALTER TABLE inventory ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0 CHECK (rating_count >= 0);
ALTER TABLE inventory ADD COLUMN created_at TIMESTAMP(6) NOT NULL DEFAULT now();
-- What search matches: name and brand count most, then category, then the description
ALTER TABLE inventory ADD COLUMN search_vector TSVECTOR;
-- Name, brand and category names in lower case without accents: matched by trigrams for typos and
-- partial words ("hedphones", "kurt"), including words found only in the category ("headphones")
ALTER TABLE inventory ADD COLUMN search_text TEXT;

-- Clothing and footwear: GST follows the price (5% up to Rs 2,500 a piece, 18% above)
ALTER TABLE categories ADD COLUMN gst_rule VARCHAR(20) CHECK (gst_rule IN ('APPAREL_2500'));

CREATE OR REPLACE FUNCTION product_search_vector(p_name TEXT, p_brand TEXT, p_description TEXT, p_category BIGINT)
    RETURNS TSVECTOR LANGUAGE sql STABLE AS $$
    SELECT setweight(to_tsvector('english', f_unaccent(coalesce(p_name, ''))), 'A')
        || setweight(to_tsvector('english', f_unaccent(coalesce(p_brand, ''))), 'A')
        || setweight(to_tsvector('english', f_unaccent(coalesce(
               (SELECT c.name || ' ' || coalesce(p.name, '')
                  FROM categories c LEFT JOIN categories p ON p.category_id = c.parent_id
                 WHERE c.category_id = p_category), ''))), 'B')
        || setweight(to_tsvector('english', f_unaccent(coalesce(p_description, ''))), 'C')
$$;

CREATE OR REPLACE FUNCTION product_search_text(p_name TEXT, p_brand TEXT, p_category BIGINT)
    RETURNS TEXT LANGUAGE sql STABLE AS $$
    SELECT f_unaccent(lower(concat_ws(' ', p_name, p_brand,
        (SELECT c.name || ' ' || coalesce(p.name, '')
           FROM categories c LEFT JOIN categories p ON p.category_id = c.parent_id
          WHERE c.category_id = p_category))))
$$;

CREATE OR REPLACE FUNCTION inventory_search_refresh() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.search_vector := product_search_vector(NEW.product_name, NEW.brand, NEW.description, NEW.category_id);
    NEW.search_text := product_search_text(NEW.product_name, NEW.brand, NEW.category_id);
    RETURN NEW;
END $$;

CREATE TRIGGER inventory_search
    BEFORE INSERT OR UPDATE OF product_name, brand, description, category_id ON inventory
    FOR EACH ROW EXECUTE FUNCTION inventory_search_refresh();

-- A renamed or moved category changes what its products are found by
CREATE OR REPLACE FUNCTION category_search_refresh() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE inventory i SET search_vector = product_search_vector(i.product_name, i.brand, i.description, i.category_id),
                           search_text = product_search_text(i.product_name, i.brand, i.category_id)
     WHERE i.category_id = NEW.category_id
        OR i.category_id IN (SELECT category_id FROM categories WHERE parent_id = NEW.category_id);
    RETURN NEW;
END $$;

CREATE TRIGGER category_search
    AFTER UPDATE OF name, parent_id ON categories
    FOR EACH ROW EXECUTE FUNCTION category_search_refresh();

UPDATE inventory SET search_vector = product_search_vector(product_name, brand, description, category_id),
                     search_text = product_search_text(product_name, brand, category_id);

CREATE INDEX inventory_search_idx ON inventory USING gin (search_vector);
-- Typos and partial words ("hedphone", "kurt") by trigram similarity
CREATE INDEX inventory_search_trgm ON inventory USING gin (search_text gin_trgm_ops);
CREATE INDEX inventory_brand ON inventory (lower(brand));
CREATE INDEX inventory_price ON inventory (unit_price) WHERE active;

-- ── Specifications ──────────────────────────────────────────────────────────

CREATE TABLE product_specs (
    sku      VARCHAR(64)  NOT NULL REFERENCES inventory (sku),
    name     VARCHAR(60)  NOT NULL,
    value    VARCHAR(200) NOT NULL,
    position INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (sku, name)
);

-- The specification names a category suggests in the admin, e.g. Smartphones: RAM, Storage, Screen
CREATE TABLE category_spec_keys (
    category_id BIGINT      NOT NULL REFERENCES categories (category_id) ON DELETE CASCADE,
    name        VARCHAR(60) NOT NULL,
    position    INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (category_id, name)
);

-- ── Reviews ─────────────────────────────────────────────────────────────────

-- Only from a customer who received the product; shown once an admin approves it
CREATE TABLE reviews (
    review_id     BIGSERIAL    PRIMARY KEY,
    sku           VARCHAR(64)  NOT NULL REFERENCES inventory (sku),
    customer_id   VARCHAR(255) NOT NULL REFERENCES app_users (user_id),
    order_id      VARCHAR(255) NOT NULL REFERENCES orders (order_id),
    rating        SMALLINT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    title         VARCHAR(120),
    body          VARCHAR(2000),
    status        VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at    TIMESTAMP(6) NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP(6),
    decided_at    TIMESTAMP(6),
    decided_by    VARCHAR(255),
    decision_note VARCHAR(500),
    UNIQUE (sku, customer_id)
);

CREATE INDEX reviews_product ON reviews (sku, created_at DESC) WHERE status = 'APPROVED';
CREATE INDEX reviews_queue ON reviews (created_at) WHERE status = 'PENDING';

-- ── Wishlists ───────────────────────────────────────────────────────────────

CREATE TABLE wishlist_items (
    customer_id           VARCHAR(255) NOT NULL REFERENCES app_users (user_id),
    sku                   VARCHAR(64)  NOT NULL REFERENCES inventory (sku),
    created_at            TIMESTAMP(6) NOT NULL DEFAULT now(),
    -- "Email me when it is back": sent once, when the product is in stock again
    notify_when_available BOOLEAN      NOT NULL DEFAULT FALSE,
    notified_at           TIMESTAMP(6),
    PRIMARY KEY (customer_id, sku)
);

CREATE INDEX wishlist_waiting ON wishlist_items (sku) WHERE notify_when_available AND notified_at IS NULL;

-- ── PIN codes (India Post directory) ────────────────────────────────────────

CREATE TABLE pin_codes (
    pin        CHAR(6)      PRIMARY KEY CHECK (pin ~ '^[1-9][0-9]{5}$'),
    district   VARCHAR(100) NOT NULL,
    -- GST state code, as in IndianState
    state_code CHAR(2)      NOT NULL,
    state_name VARCHAR(100) NOT NULL
);
