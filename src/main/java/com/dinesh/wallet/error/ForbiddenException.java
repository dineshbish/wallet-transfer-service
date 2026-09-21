package com.dinesh.wallet.error;

/**
 * Thrown when an authenticated caller tries to act on a wallet they do not own
 * (e.g. transferring out of, or reading, someone else's wallet). Maps to HTTP 403.
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
