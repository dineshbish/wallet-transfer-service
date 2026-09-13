-- Wallets. One row per user.
--
-- Invariant enforcement that lives here (not in app code):
--   * UNIQUE(user_id)            -> race-free get-or-create. Two concurrent
--                                   inserts for the same user cannot both win.
--   * CHECK(balance_paise >= 0)  -> no-overdraft backstop. Even a buggy debit
--                                   can never persist a negative balance.
--   * balance_paise is BIGINT    -> money is always integer paise, never a float.

CREATE TABLE wallets (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       VARCHAR(255) NOT NULL,
    balance_paise BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_id     UNIQUE (user_id),
    CONSTRAINT ck_wallets_no_overdraft CHECK (balance_paise >= 0)
);
