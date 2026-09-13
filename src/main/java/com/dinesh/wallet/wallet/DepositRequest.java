package com.dinesh.wallet.wallet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record DepositRequest(
        @Positive(message = "must be a positive integer number of paise")
        long amountPaise,

        @NotBlank(message = "is required")
        String idempotencyKey
) {
}
