-- Money as exact decimals, a currency on every order and payment, and Indian GST on every line.
-- Orders placed before this release were priced in US dollars without tax; they keep that.

-- ── Orders ───────────────────────────────────────────────────────────────────
ALTER TABLE orders
    ALTER COLUMN total TYPE NUMERIC(12, 2) USING round(total::numeric, 2),
    ALTER COLUMN shipping_cost TYPE NUMERIC(12, 2) USING round(shipping_cost::numeric, 2),
    ADD COLUMN currency        VARCHAR(3) CHECK (currency ~ '^[A-Z]{3}$'),
    ADD COLUMN subtotal        NUMERIC(12, 2),
    ADD COLUMN cgst_total      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_total      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_total      NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN tax_total       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    -- GST state code of the delivery address
    ADD COLUMN place_of_supply VARCHAR(2) CHECK (place_of_supply ~ '^[0-9]{2}$'),
    ADD COLUMN seller_gstin    VARCHAR(15);

UPDATE orders SET currency = 'USD', subtotal = total;

ALTER TABLE orders
    ALTER COLUMN currency SET NOT NULL,
    ALTER COLUMN currency SET DEFAULT 'INR',
    ALTER COLUMN subtotal SET NOT NULL,
    ADD CONSTRAINT orders_amounts_add_up CHECK (
        tax_total = cgst_total + sgst_total + igst_total
        AND total = subtotal + tax_total
        AND subtotal >= 0 AND tax_total >= 0);

-- ── Order lines: a snapshot of price and tax when the order was placed ─────────
ALTER TABLE order_items
    ALTER COLUMN price TYPE NUMERIC(12, 2) USING round(price::numeric, 2),
    ADD COLUMN product_name  VARCHAR(255),
    ADD COLUMN hsn_code      VARCHAR(8),
    ADD COLUMN gst_rate      NUMERIC(5, 2) NOT NULL DEFAULT 0,
    ADD COLUMN taxable_value NUMERIC(12, 2),
    ADD COLUMN cgst_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN sgst_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN igst_amount   NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN line_total    NUMERIC(12, 2);

UPDATE order_items SET taxable_value = round(price * quantity, 2), line_total = round(price * quantity, 2);

ALTER TABLE order_items
    ALTER COLUMN taxable_value SET NOT NULL,
    ALTER COLUMN line_total SET NOT NULL,
    ADD CONSTRAINT order_items_amounts_add_up CHECK (
        line_total = taxable_value + cgst_amount + sgst_amount + igst_amount
        AND cgst_amount = sgst_amount
        AND (igst_amount = 0 OR cgst_amount = 0));

-- ── Catalogue: HSN code and GST rate per product ────────────────────────────
ALTER TABLE inventory
    ADD COLUMN hsn_code VARCHAR(8),
    -- Rates in force: 0, 0.25, 3, 5 and 18, 40 for luxury goods; 12 and 28 for older registrations
    ADD COLUMN gst_rate NUMERIC(5, 2) NOT NULL DEFAULT 18
        CHECK (gst_rate IN (0, 0.25, 3, 5, 12, 18, 28, 40));

-- The demo catalogue moves from dollars to rupees, GST included
UPDATE inventory SET unit_price = 8499.00, hsn_code = '9401' WHERE sku = 'WIDGET-A';
UPDATE inventory SET unit_price = 3299.00, hsn_code = '8471' WHERE sku = 'WIDGET-B';
UPDATE inventory SET unit_price = 4999.00, hsn_code = '8302' WHERE sku = 'GADGET-X';
UPDATE inventory SET unit_price = 84999.00, hsn_code = '9403' WHERE sku = 'SERVER-PRO';
UPDATE inventory SET unit_price = 999.00, hsn_code = '9403' WHERE sku = 'OUT-OF-STOCK';

-- ── Payments: every charge and refund records its currency ────────────────────
ALTER TABLE payment_transactions ADD COLUMN currency VARCHAR(3) CHECK (currency ~ '^[A-Z]{3}$');
UPDATE payment_transactions SET currency = 'USD';
ALTER TABLE payment_transactions ALTER COLUMN currency SET NOT NULL;

-- ── GST document numbers: consecutive per series and financial year ────────────
-- invoice_number_seq stays for the numbers it already issued
CREATE TABLE document_sequences (
    series         VARCHAR(8) NOT NULL,
    financial_year CHAR(5)    NOT NULL,
    last_number    BIGINT     NOT NULL,
    PRIMARY KEY (series, financial_year)
);
