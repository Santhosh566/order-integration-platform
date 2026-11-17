-- What a driver earns for each finished attempt, fixed when it finishes, so a later change of rates
-- does not rewrite past earnings
ALTER TABLE delivery_assignments ADD COLUMN pay_amount NUMERIC(10, 2);
ALTER TABLE return_pickups ADD COLUMN pay_amount NUMERIC(10, 2);

-- Earlier attempts at the rates in force when this was introduced
UPDATE delivery_assignments SET pay_amount = 40.00 WHERE status = 'DELIVERED';
UPDATE delivery_assignments SET pay_amount = 15.00 WHERE status = 'FAILED' AND picked_up_at IS NOT NULL;
UPDATE return_pickups SET pay_amount = 35.00 WHERE status = 'COLLECTED';
UPDATE return_pickups SET pay_amount = 15.00 WHERE status = 'FAILED' AND started_at IS NOT NULL;

-- The customer's rating of how their order was delivered: one per order, for the driver who delivered it
CREATE TABLE delivery_ratings (
    order_id         VARCHAR(255) PRIMARY KEY REFERENCES orders (order_id),
    assignment_id    BIGINT       NOT NULL REFERENCES delivery_assignments (assignment_id),
    delivery_user_id VARCHAR(255) NOT NULL REFERENCES app_users (user_id),
    customer_id      VARCHAR(255) NOT NULL,
    rating           SMALLINT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment          VARCHAR(500),
    created_at       TIMESTAMP(6) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL
);
CREATE INDEX delivery_ratings_driver ON delivery_ratings (delivery_user_id, created_at);
