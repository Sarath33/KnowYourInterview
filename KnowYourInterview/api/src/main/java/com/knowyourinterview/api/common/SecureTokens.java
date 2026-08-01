package com.knowyourinterview.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing for the link-borne tokens the app emails out — password reset and
 * email confirmation.
 * <p>
 * Extracted from AuthService when email confirmation became the second feature needing
 * exactly this: 256 bits from a CSPRNG, url-safe encoded, stored only as a SHA-256 hash. Two
 * copies would have been tolerable; three is where a subtly weaker one eventually appears.
 * <p>
 * SHA-256 without a salt or work factor is correct here, unlike for passwords: these tokens
 * are 256 bits of uniform randomness, so there's no dictionary to attack and nothing for a
 * slow hash to buy. What the hash is for is making a database leak useless — the raw token
 * lives only in the user's inbox.
 */
public final class SecureTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private SecureTokens() {
    }

    /** A fresh url-safe token, suitable for putting straight into a link's query string. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex SHA-256 — the form stored in {@code token_hash} columns. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM — unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
