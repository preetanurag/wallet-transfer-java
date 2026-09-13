CREATE TABLE wallets (
    id UUID PRIMARY KEY,
    user_id TEXT NOT NULL UNIQUE,
    balance_paise BIGINT NOT NULL DEFAULT 0
        CHECK (balance_paise BETWEEN 0 AND 9007199254740991)
);

CREATE TABLE transfers (
    id UUID PRIMARY KEY,
    user_id TEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) > 0),
    from_wallet UUID NOT NULL REFERENCES wallets(id),
    to_wallet UUID NOT NULL REFERENCES wallets(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise BETWEEN 1 AND 9007199254740991),
    status VARCHAR(16) NOT NULL CHECK (status IN ('succeeded', 'declined')),
    reason VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key),
    CHECK (from_wallet <> to_wallet),
    CHECK ((status = 'succeeded' AND reason IS NULL) OR
           (status = 'declined' AND reason IS NOT NULL AND
            reason IN ('insufficient_funds', 'recipient_balance_limit')))
);
