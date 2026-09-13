package com.dinesh.wallet.wallet;

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

    public Wallet getById(UUID id) {
        return wallets.findById(id)
                .orElseThrow(() -> new NotFoundException("wallet not found: " + id));
    }

    /**
     * Adds outside funds to a wallet (the funding boundary). Idempotent: a retry
     * with the same key does not credit twice. The credit is an atomic increment,
     * so concurrent deposits with distinct keys all apply without lost updates.
     */
    @Transactional
    public Wallet deposit(UUID walletId, long amountPaise, String idempotencyKey) {
        Wallet wallet = getById(walletId);
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
}
