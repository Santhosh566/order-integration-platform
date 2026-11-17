-- Customer messages, saved with the order change they describe and sent afterwards
CREATE TABLE customer_notifications (
    id              VARCHAR(36) PRIMARY KEY,
    order_id        VARCHAR(255) NOT NULL,
    customer_id     VARCHAR(255) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    text            TEXT,
    -- The text is removed once sent, as for the delivery code
    secret          BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts        INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMP(6) NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    claimed_at      TIMESTAMP(6),
    sent_at         TIMESTAMP(6)
);

CREATE INDEX customer_notifications_due ON customer_notifications (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX customer_notifications_order ON customer_notifications (order_id);
