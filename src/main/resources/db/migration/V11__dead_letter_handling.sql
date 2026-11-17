-- Who replayed a dead letter, and dead letters an operator closed without replaying them
-- (for example the order was cancelled meanwhile). Open = not replayed and not dismissed.
ALTER TABLE dead_letter_events ADD COLUMN replayed_by  VARCHAR(255);
ALTER TABLE dead_letter_events ADD COLUMN dismissed_at TIMESTAMP(6);
ALTER TABLE dead_letter_events ADD COLUMN dismissed_by VARCHAR(255);
ALTER TABLE dead_letter_events ADD COLUMN dismiss_note VARCHAR(500);

CREATE INDEX idx_dead_letter_events_open ON dead_letter_events (failed_at)
    WHERE replayed = FALSE AND dismissed_at IS NULL;
