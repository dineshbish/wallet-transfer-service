package com.dinesh.wallet.transfer;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the idempotency key by inserting a PENDING transfer, returning the
     * number of rows inserted: 1 if this caller won the claim, 0 if the key
     * already exists.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is deliberate. A raw INSERT that hit the
     * UNIQUE violation would raise an error and abort the whole Postgres
     * transaction ("current transaction is aborted"), so we could not then read
     * the existing row in the same tx. ON CONFLICT resolves the duplicate without
     * an error, keeping the transaction healthy. A concurrent same-key insert
     * still blocks here until the other transaction commits (then this returns 0)
     * or rolls back (then this inserts) &mdash; giving exactly-once with no TOCTOU.
     */
    public int insertPendingIfAbsent(Transfer transfer) {
        var params = new MapSqlParameterSource()
                .addValue("id", transfer.id())
                .addValue("key", transfer.idempotencyKey())
                .addValue("from", transfer.fromWalletId())
                .addValue("to", transfer.toWalletId())
                .addValue("amount", transfer.amountPaise())
                .addValue("hash", transfer.requestHash());
        return jdbc.update("""
                INSERT INTO transfers
                    (id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status, request_hash)
                VALUES
                    (:id, :key, :from, :to, :amount, 'PENDING', :hash)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, params);
    }

    public void updateStatus(UUID id, TransferStatus status) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name());
        jdbc.update("UPDATE transfers SET status = :status WHERE id = :id", params);
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query("SELECT * FROM transfers WHERE id = :id",
                        new MapSqlParameterSource("id", id), TransferRepository::mapRow)
                .stream().findFirst();
    }

    public Optional<Transfer> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM transfers WHERE idempotency_key = :key",
                        new MapSqlParameterSource("key", key), TransferRepository::mapRow)
                .stream().findFirst();
    }

    private static Transfer mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getObject("from_wallet_id", UUID.class),
                rs.getObject("to_wallet_id", UUID.class),
                rs.getLong("amount_paise"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getString("request_hash"),
                rs.getTimestamp("created_at").toInstant());
    }
}
