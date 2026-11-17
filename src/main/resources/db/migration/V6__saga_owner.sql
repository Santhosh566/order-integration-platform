-- The saga working on the order. A compensation from any other saga, such as one the LRA
-- coordinator cancels after recovery has already taken the order over, is ignored.
ALTER TABLE orders ADD COLUMN saga_id VARCHAR(255);
