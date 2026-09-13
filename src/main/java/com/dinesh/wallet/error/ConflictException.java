package com.dinesh.wallet.error;

/**
 * Thrown when an idempotency key is reused with a different request body. Maps to
 * HTTP 409 so a client cannot silently get the first result for a different intent.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
