package com.dinesh.wallet.transfer;

import com.dinesh.wallet.error.BadRequestException;
import com.dinesh.wallet.error.ConflictException;
import com.dinesh.wallet.error.ForbiddenException;
import com.dinesh.wallet.error.NotFoundException;
import com.dinesh.wallet.observability.TransferMetrics;
import com.dinesh.wallet.wallet.Wallet;
import com.dinesh.wallet.wallet.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transfers;
    private final WalletRepository wallets;
    private final TransferMetrics metrics;

    public TransferService(TransferRepository transfers,
                           WalletRepository wallets,
                           TransferMetrics metrics) {
        this.transfers = transfers;
        this.wallets = wallets;
        this.metrics = metrics;
    }

    /**
     * Moves {@code amountPaise} from one wallet to another, exactly once per
     * idempotency key.
     *
     * <p>Everything below runs in a single transaction (READ COMMITTED). The
     * ordering matters:
     * <ol>
     *   <li>Validate and confirm both wallets exist, so a bad request is a clean
     *       4xx rather than a foreign-key violation that would poison the tx.</li>
     *   <li>Claim the idempotency key with INSERT ... ON CONFLICT DO NOTHING. If
     *       the key already exists it is a replay (same body &rarr; return the
     *       original) or a conflict (different body &rarr; 409). The claim is
     *       committed in the same tx as the money move, so it is TOCTOU-free.</li>
     *   <li>Lock both wallet rows FOR UPDATE in ascending id order, giving every
     *       transfer a single global lock order &mdash; this is what stops an
     *       A&rarr;B and B&rarr;A pair from deadlocking.</li>
     *   <li>Debit with an atomic conditional UPDATE (rows affected = 0 means the
     *       balance was insufficient &rarr; decline cleanly, no partial apply),
     *       then credit. Debit-before-credit means a decline needs no rollback of
     *       a credit.</li>
     * </ol>
     */
    @Transactional
    public Transfer transfer(String callerUserId, UUID from, UUID to, long amountPaise, String idempotencyKey) {
        if (from.equals(to)) {
            throw new BadRequestException("from and to wallets must be different");
        }
        // Confirm both wallets exist up front (clean 404 instead of an FK violation).
        Wallet fromWallet = wallets.findById(from)
                .orElseThrow(() -> new NotFoundException("source wallet not found: " + from));
        if (wallets.findById(to).isEmpty()) {
            throw new NotFoundException("destination wallet not found: " + to);
        }

        // Authorization: the caller may only move money out of a wallet they own.
        // The sender is bound to the auth token, not trusted from the request body,
        // so a caller cannot drain someone else's wallet by naming it in `from`.
        if (!fromWallet.userId().equals(callerUserId)) {
            log.info("transfer.forbidden caller={} from={} from_owner={}",
                    callerUserId, from, fromWallet.userId());
            throw new ForbiddenException("caller does not own source wallet " + from);
        }

        String requestHash = hash(from, to, amountPaise);
        UUID transferId = UUID.randomUUID();

        Transfer pending = new Transfer(
                transferId, idempotencyKey, from, to, amountPaise,
                TransferStatus.PENDING, requestHash, null);

        // Step 2: establish the global lock order FIRST — lock both wallet rows
        // FOR UPDATE in ascending id order, before any other row lock in this
        // transaction. This must precede the transfers INSERT: that INSERT's
        // foreign keys take KEY SHARE locks on these same wallet rows (in
        // unsorted from/to order), and a later FOR UPDATE would try to upgrade
        // them, which deadlocks two opposite-direction transfers. Locking FOR
        // UPDATE up front means the FK's KEY SHARE is already subsumed.
        wallets.lockInOrder(sortedIds(from, to));

        // Step 3: claim the key. Zero rows affected => the key already exists.
        int claimed = transfers.insertPendingIfAbsent(pending);
        if (claimed == 0) {
            return handleExistingKey(idempotencyKey, requestHash);
        }

        log.info("transfer.created transfer_id={} from={} to={} amount_paise={}",
                transferId, from, to, amountPaise);

        // Step 4: atomic conditional debit.
        int debited = wallets.debitIfSufficient(from, amountPaise);
        if (debited == 0) {
            transfers.updateStatus(transferId, TransferStatus.DECLINED);
            metrics.declinedInsufficientFunds();
            log.info("transfer.declined transfer_id={} from={} amount_paise={} reason=insufficient_funds",
                    transferId, from, amountPaise);
            return withStatus(pending, TransferStatus.DECLINED);
        }
        log.info("transfer.debited transfer_id={} from={} amount_paise={}", transferId, from, amountPaise);

        wallets.credit(to, amountPaise);
        log.info("transfer.credited transfer_id={} to={} amount_paise={}", transferId, to, amountPaise);

        transfers.updateStatus(transferId, TransferStatus.COMPLETED);
        metrics.completed();
        log.info("transfer.completed transfer_id={} from={} to={} amount_paise={}",
                transferId, from, to, amountPaise);
        return withStatus(pending, TransferStatus.COMPLETED);
    }

    /** Internal lookup with no ownership check; used by tests. */
    public Transfer getById(UUID id) {
        return transfers.findById(id)
                .orElseThrow(() -> new NotFoundException("transfer not found: " + id));
    }

    /**
     * Reads a transfer, enforcing that {@code callerUserId} is a party to it — the
     * owner of either the source or the destination wallet. Otherwise 403.
     */
    public Transfer getVisibleById(String callerUserId, UUID id) {
        Transfer transfer = getById(id);
        String fromOwner = wallets.findById(transfer.fromWalletId()).map(Wallet::userId).orElse(null);
        String toOwner = wallets.findById(transfer.toWalletId()).map(Wallet::userId).orElse(null);
        if (!callerUserId.equals(fromOwner) && !callerUserId.equals(toOwner)) {
            throw new ForbiddenException("caller is not a party to transfer " + id);
        }
        return transfer;
    }

    private Transfer handleExistingKey(String idempotencyKey, String requestHash) {
        Transfer existing = transfers.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key vanished after conflict: " + idempotencyKey));
        if (!existing.requestHash().equals(requestHash)) {
            metrics.conflict();
            log.info("transfer.conflict idempotency_key={} existing_transfer_id={}",
                    idempotencyKey, existing.id());
            throw new ConflictException(
                    "idempotency key reused with a different request body");
        }
        metrics.idempotentReplay();
        log.info("transfer.idempotent_replay idempotency_key={} transfer_id={} status={}",
                idempotencyKey, existing.id(), existing.status());
        return existing;
    }

    private static List<UUID> sortedIds(UUID a, UUID b) {
        return List.of(a, b).stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static Transfer withStatus(Transfer t, TransferStatus status) {
        return new Transfer(t.id(), t.idempotencyKey(), t.fromWalletId(), t.toWalletId(),
                t.amountPaise(), status, t.requestHash(), t.createdAt());
    }

    /** Hash of the meaningful body fields, used to detect same-key/different-body replays. */
    private static String hash(UUID from, UUID to, long amountPaise) {
        String canonical = from + "|" + to + "|" + amountPaise;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
