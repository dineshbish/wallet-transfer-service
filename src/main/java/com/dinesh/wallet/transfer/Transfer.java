package com.dinesh.wallet.transfer;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        String idempotencyKey,
        UUID fromWalletId,
        UUID toWalletId,
        long amountPaise,
        TransferStatus status,
        String requestHash,
        Instant createdAt
) {
}
