-- Transfers. Also serves as the idempotency ledger.
--
-- Invariant enforcement that lives here (not in app code):
--   * UNIQUE(idempotency_key)  -> exactly-once. The key is claimed by INSERT in
--                                 the SAME transaction as the debit/credit, so a
--                                 concurrent retry either loses the insert race
--                                 (and reads the committed result) or blocks until
--                                 the winner commits. No TOCTOU double-debit.
--   * request_hash             -> a same-key/different-body replay is detected and
--                                 rejected with 409, instead of silently returning
--                                 the first result or applying a second debit.
--   * amount_paise > 0         -> transfers only ever move a positive integer.
--   * status                   -> PENDING while the money move runs inside the tx,
--                                 then COMPLETED or DECLINED at commit.

CREATE TABLE transfers (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    from_wallet_id  UUID         NOT NULL REFERENCES wallets(id),
    to_wallet_id    UUID         NOT NULL REFERENCES wallets(id),
    amount_paise    BIGINT       NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT ck_transfers_status          CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED')),
    CONSTRAINT ck_transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id)
);

CREATE INDEX idx_transfers_from_wallet ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet   ON transfers (to_wallet_id);
