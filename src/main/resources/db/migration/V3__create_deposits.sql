-- Deposits: the funding boundary where money ENTERS the system (a top-up).
--
-- Conservation (invariant #1) is about transfers, which only move money between
-- existing wallets. Deposits are the explicit, auditable entry point for outside
-- funds, kept in their own ledger so every balance change is traceable to either
-- a deposit or a transfer. Idempotency is enforced the same way as transfers:
-- UNIQUE(idempotency_key), claimed in the same transaction as the credit.

CREATE TABLE deposits (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    wallet_id       UUID         NOT NULL REFERENCES wallets(id),
    amount_paise    BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_deposits_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_deposits_amount_positive CHECK (amount_paise > 0)
);

CREATE INDEX idx_deposits_wallet ON deposits (wallet_id);
