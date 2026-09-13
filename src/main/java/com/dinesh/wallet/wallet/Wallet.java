package com.dinesh.wallet.wallet;

import java.time.Instant;
import java.util.UUID;

public record Wallet(
        UUID id,
        String userId,
        long balancePaise,
        Instant createdAt,
        Instant updatedAt
) {
}
