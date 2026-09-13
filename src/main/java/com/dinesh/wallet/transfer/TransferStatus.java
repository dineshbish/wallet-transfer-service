package com.dinesh.wallet.transfer;

public enum TransferStatus {
    /** Claimed inside the transaction while the money move runs. */
    PENDING,
    /** Debit and credit both applied. */
    COMPLETED,
    /** Debit would have overdrawn the source wallet; nothing was applied. */
    DECLINED
}
