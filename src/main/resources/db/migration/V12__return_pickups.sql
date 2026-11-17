-- Return pickups: a delivery user collects approved returns from the customer, the reverse of a
-- delivery. Each attempt is a row, like delivery_assignments; the customer gives the driver a
-- one-time code to confirm the hand-over. The refund still follows the inspection at the hub.

ALTER TABLE return_requests DROP CONSTRAINT return_requests_status_check;
ALTER TABLE return_requests ADD CONSTRAINT return_requests_status_check
    CHECK (status IN ('REQUESTED', 'APPROVED', 'PICKED_UP', 'REJECTED', 'REFUNDED'));
ALTER TABLE return_requests ADD COLUMN picked_up_at TIMESTAMP(6);
ALTER TABLE return_requests ADD COLUMN picked_up_by VARCHAR(255);

DROP INDEX return_requests_open;
CREATE INDEX return_requests_open ON return_requests (status) WHERE status IN ('REQUESTED', 'APPROVED', 'PICKED_UP');

CREATE TABLE return_pickups (
    pickup_id        BIGSERIAL    PRIMARY KEY,
    return_id        VARCHAR(40)  NOT NULL REFERENCES return_requests (return_id),
    delivery_user_id VARCHAR(255) NOT NULL REFERENCES app_users (user_id),
    status           VARCHAR(16)  NOT NULL
                     CHECK (status IN ('ASSIGNED', 'ON_THE_WAY', 'COLLECTED', 'FAILED', 'REASSIGNED', 'CANCELLED')),
    attempt_number   INTEGER      NOT NULL,
    assigned_at      TIMESTAMP(6) NOT NULL,
    started_at       TIMESTAMP(6),
    collected_at     TIMESTAMP(6),
    failed_at        TIMESTAMP(6),
    failure_reason   VARCHAR(255),
    -- Hash of the customer's one-time code, never the code; cleared once used
    code_hash        VARCHAR(64),
    code_attempts    INTEGER      NOT NULL DEFAULT 0,
    UNIQUE (return_id, attempt_number)
);

-- One driver at a time per return, even if two take it at the same moment
CREATE UNIQUE INDEX return_pickups_one_live ON return_pickups (return_id)
    WHERE status IN ('ASSIGNED', 'ON_THE_WAY');
CREATE INDEX return_pickups_driver ON return_pickups (delivery_user_id)
    WHERE status IN ('ASSIGNED', 'ON_THE_WAY');
