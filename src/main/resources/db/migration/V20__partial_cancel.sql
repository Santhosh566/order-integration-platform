-- Partial cancel before dispatch: units of a line can be cancelled while the order is still in the
-- warehouse. The invoice keeps the ordered quantity; a CANCELLATION credit note gives the cancelled
-- units back, and these columns say how many units and how much money that was.
ALTER TABLE order_items
    ADD COLUMN cancelled_quantity INTEGER NOT NULL DEFAULT 0 CHECK (cancelled_quantity >= 0),
    ADD CONSTRAINT order_items_cancel_within_quantity CHECK (cancelled_quantity <= quantity);

-- The money value of cancelled units, GST included. A prepaid order also has it in refunded_total;
-- a cash-on-delivery order not paid yet simply owes less at the door.
ALTER TABLE orders
    ADD COLUMN cancelled_total NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (cancelled_total >= 0),
    ADD CONSTRAINT orders_cancel_within_total CHECK (cancelled_total <= total);

ALTER TABLE credit_notes DROP CONSTRAINT credit_notes_kind_check;
ALTER TABLE credit_notes ALTER COLUMN kind TYPE VARCHAR(12);
ALTER TABLE credit_notes ADD CONSTRAINT credit_notes_kind_check
    CHECK (kind IN ('RETURN', 'GOODWILL', 'CANCELLATION'));
-- A cancellation on an unpaid cash-on-delivery order moves no money, so it has no refund payment
ALTER TABLE credit_notes ALTER COLUMN payment_id DROP NOT NULL;
