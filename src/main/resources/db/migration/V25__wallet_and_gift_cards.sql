-- A store-credit wallet per customer, and gift cards that are redeemed into it.

-- ── Wallets ────────────────────────────────────────────────────────────────
-- The row holds the balance and is locked while it changes, so two orders cannot both spend the last
-- rupee. Every change is a row in wallet_transactions with the balance after it, so the history always
-- adds up to the balance. Wallet money never expires and is never paid out as cash.

CREATE TABLE wallets (
    customer_id  VARCHAR(255)   PRIMARY KEY REFERENCES app_users (user_id),
    balance      NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    currency     VARCHAR(3)     NOT NULL DEFAULT 'INR',
    updated_at   TIMESTAMP(6)   NOT NULL DEFAULT now()
);

CREATE TABLE wallet_transactions (
    wallet_txn_id  BIGSERIAL      PRIMARY KEY,
    customer_id    VARCHAR(255)   NOT NULL REFERENCES wallets (customer_id),
    -- GIFT_CARD and ADMIN_CREDIT add money, ORDER_PAYMENT takes it, ORDER_REFUND gives it back,
    -- ADMIN_DEBIT corrects a mistake
    kind           VARCHAR(16)    NOT NULL
                   CHECK (kind IN ('GIFT_CARD', 'ORDER_PAYMENT', 'ORDER_REFUND', 'ADMIN_CREDIT', 'ADMIN_DEBIT')),
    -- Positive adds to the balance, negative takes from it
    amount         NUMERIC(12, 2) NOT NULL CHECK (amount <> 0),
    balance_after  NUMERIC(12, 2) NOT NULL CHECK (balance_after >= 0),
    -- Shown in the customer's history, e.g. "WAL-3F2A9C1E"
    reference      VARCHAR(32)    NOT NULL UNIQUE,
    order_id       VARCHAR(255)   REFERENCES orders (order_id),
    gift_card_id   BIGINT,
    note           VARCHAR(200),
    created_by     VARCHAR(255),
    created_at     TIMESTAMP(6)   NOT NULL DEFAULT now(),
    CHECK (kind NOT IN ('ORDER_PAYMENT', 'ORDER_REFUND') OR order_id IS NOT NULL),
    CHECK (kind <> 'GIFT_CARD' OR gift_card_id IS NOT NULL),
    CHECK ((kind IN ('ORDER_PAYMENT', 'ADMIN_DEBIT')) = (amount < 0))
);

CREATE INDEX wallet_transactions_by_customer ON wallet_transactions (customer_id, created_at DESC);
CREATE INDEX wallet_transactions_by_order ON wallet_transactions (order_id) WHERE order_id IS NOT NULL;

-- ── Orders paid partly or wholly from the wallet ───────────────────────────
-- wallet_amount is what the customer chose to pay from the wallet; it is taken when the order is paid,
-- together with the card. wallet_refunded is the part of refunded_total that went back to the wallet:
-- refunds go to the card first, so the wallet gets back only what the card cannot.

ALTER TABLE orders ADD COLUMN wallet_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (wallet_amount >= 0);
ALTER TABLE orders ADD COLUMN wallet_refunded NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (wallet_refunded >= 0);
ALTER TABLE orders ADD CONSTRAINT orders_wallet_not_cod CHECK (wallet_amount = 0 OR payment_method <> 'COD');

-- ── Gift cards ─────────────────────────────────────────────────────────────
-- A code is money to whoever holds it, so only its SHA-256 is stored; admins see the full codes once,
-- when the batch is issued. A card is redeemed whole into one customer's wallet.

CREATE TABLE gift_cards (
    gift_card_id   BIGSERIAL      PRIMARY KEY,
    code_hash      VARCHAR(64)    NOT NULL UNIQUE,
    -- The last four characters, so admins and customers can tell cards apart
    last_four      VARCHAR(4)     NOT NULL,
    amount         NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3)     NOT NULL DEFAULT 'INR',
    -- Cards issued together, e.g. "Diwali corporate order"
    batch_label    VARCHAR(80),
    expires_at     TIMESTAMP(6)   NOT NULL,
    status         VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REDEEMED', 'VOID')),
    redeemed_by    VARCHAR(255)   REFERENCES app_users (user_id),
    redeemed_at    TIMESTAMP(6),
    voided_by      VARCHAR(255),
    voided_at      TIMESTAMP(6),
    void_reason    VARCHAR(200),
    created_by     VARCHAR(255),
    created_at     TIMESTAMP(6)   NOT NULL DEFAULT now(),
    CHECK ((status = 'REDEEMED') = (redeemed_by IS NOT NULL AND redeemed_at IS NOT NULL)),
    CHECK ((status = 'VOID') = (voided_at IS NOT NULL))
);

CREATE INDEX gift_cards_by_created ON gift_cards (created_at DESC);

ALTER TABLE wallet_transactions ADD CONSTRAINT wallet_transactions_gift_card
    FOREIGN KEY (gift_card_id) REFERENCES gift_cards (gift_card_id);
CREATE UNIQUE INDEX wallet_transactions_gift_card_once ON wallet_transactions (gift_card_id) WHERE gift_card_id IS NOT NULL;

-- Codes tried by each customer, so guessing codes is cut off after a few misses
CREATE TABLE gift_card_attempts (
    attempt_id   BIGSERIAL     PRIMARY KEY,
    customer_id  VARCHAR(255)  NOT NULL,
    success      BOOLEAN       NOT NULL,
    created_at   TIMESTAMP(6)  NOT NULL DEFAULT now()
);

CREATE INDEX gift_card_attempts_recent ON gift_card_attempts (customer_id, created_at DESC);
