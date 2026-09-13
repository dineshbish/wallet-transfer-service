package com.dinesh.wallet.wallet;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WalletRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Race-free get-or-create. The {@code ON CONFLICT DO NOTHING} plus the
     * UNIQUE(user_id) constraint means two concurrent callers for the same user
     * can never create two rows: at most one INSERT wins, and both callers then
     * read the single row back.
     */
    public Wallet getOrCreate(String userId) {
        var params = new MapSqlParameterSource("userId", userId);
        jdbc.update("""
                INSERT INTO wallets (user_id)
                VALUES (:userId)
                ON CONFLICT (user_id) DO NOTHING
                """, params);
        return findByUserId(userId).orElseThrow(
                () -> new IllegalStateException("wallet missing after upsert for user " + userId));
    }

    public Optional<Wallet> findById(UUID id) {
        var rows = jdbc.query(
                "SELECT * FROM wallets WHERE id = :id",
                new MapSqlParameterSource("id", id),
                WalletRepository::mapRow);
        return rows.stream().findFirst();
    }

    public Optional<Wallet> findByUserId(String userId) {
        var rows = jdbc.query(
                "SELECT * FROM wallets WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId),
                WalletRepository::mapRow);
        return rows.stream().findFirst();
    }

    /**
     * Locks the given wallet rows FOR UPDATE in a deterministic order (ascending
     * id), one row per statement. Acquiring locks in a single, globally consistent
     * order is what makes A->B and B->A transfers deadlock-free: every transaction
     * that touches the same pair locks them in the same sequence.
     *
     * <p>Each row is locked with its own single-row {@code SELECT ... FOR UPDATE}.
     * A single {@code WHERE id IN (a, b) ORDER BY id FOR UPDATE} does NOT work:
     * Postgres acquires the row locks in scan (heap) order and only applies the
     * ORDER BY afterwards, so with random-UUID ids the actual lock order is
     * undefined and two opposite-direction transfers can still deadlock. Issuing
     * the locks one at a time in sorted order is what actually pins the order.
     */
    public void lockInOrder(List<UUID> walletIds) {
        List<UUID> ordered = walletIds.stream().sorted().toList();
        for (UUID id : ordered) {
            jdbc.query("SELECT id FROM wallets WHERE id = :id FOR UPDATE",
                    new MapSqlParameterSource("id", id),
                    rs -> { /* row discarded; the point is the lock */ });
        }
    }

    /**
     * Atomic conditional debit. Returns rows affected: 1 = debited, 0 = would
     * overdraw (declined). No read-modify-write in app code, so there is no lost
     * update even without the caller having read the balance first.
     */
    public int debitIfSufficient(UUID walletId, long amountPaise) {
        var params = new MapSqlParameterSource()
                .addValue("id", walletId)
                .addValue("amount", amountPaise);
        return jdbc.update("""
                UPDATE wallets
                SET balance_paise = balance_paise - :amount,
                    updated_at = now()
                WHERE id = :id AND balance_paise >= :amount
                """, params);
    }

    /**
     * Claims a deposit idempotency key. Returns 1 if this caller won the claim
     * (and should apply the credit), 0 if the key already exists (a retry &mdash;
     * do not credit again). ON CONFLICT keeps the transaction healthy, same as the
     * transfer path.
     */
    public int insertDepositIfAbsent(UUID depositId, String idempotencyKey, UUID walletId, long amountPaise) {
        var params = new MapSqlParameterSource()
                .addValue("id", depositId)
                .addValue("key", idempotencyKey)
                .addValue("walletId", walletId)
                .addValue("amount", amountPaise);
        return jdbc.update("""
                INSERT INTO deposits (id, idempotency_key, wallet_id, amount_paise)
                VALUES (:id, :key, :walletId, :amount)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, params);
    }

    public void credit(UUID walletId, long amountPaise) {
        var params = new MapSqlParameterSource()
                .addValue("id", walletId)
                .addValue("amount", amountPaise);
        jdbc.update("""
                UPDATE wallets
                SET balance_paise = balance_paise + :amount,
                    updated_at = now()
                WHERE id = :id
                """, params);
    }

    private static Wallet mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Wallet(
                rs.getObject("id", UUID.class),
                rs.getString("user_id"),
                rs.getLong("balance_paise"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
