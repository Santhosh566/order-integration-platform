-- A message to a customer need not be about an order: "Back in stock" is about a product on their
-- wishlist. A message still needs someone to go to: a customer, or a fixed address for staff.
ALTER TABLE customer_notifications DROP CONSTRAINT customer_notifications_has_recipient;
ALTER TABLE customer_notifications
    ADD CONSTRAINT customer_notifications_has_recipient
        CHECK (recipient_email IS NOT NULL OR customer_id IS NOT NULL);
