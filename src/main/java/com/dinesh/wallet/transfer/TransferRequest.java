package com.dinesh.wallet.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "is required")
        UUID from,

        @NotNull(message = "is required")
        UUID to,

        @Positive(message = "must be a positive integer number of paise")
        long amountPaise,

        @NotBlank(message = "is required")
        String idempotencyKey
) {
}
