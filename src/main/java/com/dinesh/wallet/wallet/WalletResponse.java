package com.dinesh.wallet.wallet;

import java.util.UUID;

public record WalletResponse(
        UUID id,
        String userId,
        long balancePaise
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.balancePaise());
    }
}
