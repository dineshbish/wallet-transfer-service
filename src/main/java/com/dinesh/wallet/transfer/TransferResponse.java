package com.dinesh.wallet.transfer;

import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID from,
        UUID to,
        long amountPaise,
        TransferStatus status
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id(),
                transfer.fromWalletId(),
                transfer.toWalletId(),
                transfer.amountPaise(),
                transfer.status());
    }
}
