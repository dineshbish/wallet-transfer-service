package com.dinesh.wallet.security;

import com.dinesh.wallet.error.UnauthorizedException;

/**
 * Holds the authenticated user id for the current request thread. Populated by
 * {@link BearerAuthFilter} and cleared when the request completes.
 */
public final class AuthContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void setUserId(String userId) {
        CURRENT_USER.set(userId);
    }

    public static String requireUserId() {
        String userId = CURRENT_USER.get();
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("missing or invalid bearer token");
        }
        return userId;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
