package com.dinesh.wallet.wallet;

import com.dinesh.wallet.error.ForbiddenException;
import com.dinesh.wallet.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;

    public WalletService(WalletRepository wallets) {
        this.wallets = wallets;
    }

    public Wallet getOrCreate(String userId) {
        Wallet wallet = wallets.getOrCreate(userId);
        log.info("wallet.get_or_create user_id={} wallet_id={} balance_paise={}",
                userId, wallet.id(), wallet.balancePaise());
        return wallet;
    }

    /** Internal lookup with no ownership check; used by other services and tests. */
    public Wallet getById(UUID id) {
        return wallets.findById(id)
                .orElseThrow(() -> new NotFoundException("wallet not found: " + id));
    }

    /**
     * Reads a wallet, enforcing that {@code callerUserId} owns it. A missing wallet
     * is 404; a wallet owned by someone else is 403 — a caller can only read their
     * own balance.
     */
    public Wallet getOwnedById(String callerUserId, UUID id) {
        return requireOwned(callerUserId, id);
    }

    /**
     * Adds outside funds to a wallet the caller owns (the funding boundary).
     * Idempotent: a retry with the same key does not credit twice. The credit is an
     * atomic increment, so concurrent deposits with distinct keys all apply without
     * lost updates.
     */
    @Transactional
    public Wallet deposit(String callerUserId, UUID walletId, long amountPaise, String idempotencyKey) {
        requireOwned(callerUserId, walletId);
        int claimed = wallets.insertDepositIfAbsent(UUID.randomUUID(), idempotencyKey, walletId, amountPaise);
        if (claimed == 1) {
            wallets.credit(walletId, amountPaise);
            log.info("wallet.deposit wallet_id={} amount_paise={} idempotency_key={}",
                    walletId, amountPaise, idempotencyKey);
        } else {
            log.info("wallet.deposit_idempotent_replay wallet_id={} idempotency_key={}",
                    walletId, idempotencyKey);
        }
        return getById(walletId);
    }

    /** Loads a wallet and asserts the caller owns it (404 if absent, 403 if not owner). */
    private Wallet requireOwned(String callerUserId, UUID walletId) {
        Wallet wallet = getById(walletId);
        if (!wallet.userId().equals(callerUserId)) {
            log.info("wallet.forbidden caller={} wallet_id={} owner={}",
                    callerUserId, walletId, wallet.userId());
            throw new ForbiddenException("caller does not own wallet " + walletId);
        }
        return wallet;
    }
}
