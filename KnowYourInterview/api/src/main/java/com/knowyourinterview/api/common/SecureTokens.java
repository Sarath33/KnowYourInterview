package com.knowyourinterview.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing for the secrets the app emails out — the password-reset link's token
 * and the registration confirmation code.
 * <p>
 * Extracted from AuthService when email confirmation became the second feature needing exactly
 * this. Two copies would have been tolerable; three is where a subtly weaker one eventually
 * appears.
 * <p>
 * <b>On hashing, and what it is and isn't worth here.</b> SHA-256 without a salt or work factor
 * is the right call for {@link #generate()}'s 256-bit tokens: there's no dictionary to attack
 * and nothing a slow hash would buy, and the hash makes a database leak useless because the raw
 * value lives only in the user's inbox. For {@link #numericCode(int)} that reasoning mostly
 * doesn't hold — a six-digit code has a million candidates, so anyone holding the hash can
 * recover it in milliseconds. It's stored hashed anyway because it costs nothing and keeps one
 * storage format across both, but it should not be mistaken for protection: what actually
 * defends a short code is its ten-minute lifetime and the cap on wrong guesses.
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

    /**
     * A zero-padded numeric one-time code, e.g. {@code "048192"} for 6 digits — the kind of
     * thing a person reads off their phone and retypes.
     * <p>
     * {@code nextInt(bound)} rather than assembling digits individually, so the distribution is
     * uniform across the whole range with no modulo bias. Zero-padding matters: without it,
     * {@code 48192} is a five-character code and looks broken next to every other one.
     * <p>
     * A code this short is only safe alongside the other two controls its caller applies — a
     * short expiry and a hard cap on wrong guesses. Six digits is a million possibilities,
     * which is nothing to a script given unlimited attempts. See
     * {@code EmailVerificationService#verify}.
     */
    public static String numericCode(int digits) {
        if (digits < 4 || digits > 9) {
            // 9 is the ceiling for an int bound of 10^digits; 4 is the floor worth allowing.
            throw new IllegalArgumentException("Code length must be between 4 and 9 digits");
        }
        int bound = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", SECURE_RANDOM.nextInt(bound));
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
