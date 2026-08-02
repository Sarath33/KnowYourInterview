package com.knowyourinterview.api.common.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Application-level AES-256-GCM field encryptor for at-rest secrets (currently the payout UPI
 * VPA). Column-level rather than TDE so the plaintext never sits in a JVM heap dump, a log line
 * or a DB backup in the clear.
 * <p>
 * <strong>Token format.</strong> {@link #encrypt} always emits a self-describing token
 * {@code "enc:v1:" + base64(iv(12 bytes) || ciphertext||gcmTag)}. The {@code v1} tag is the seam
 * for a future key-versioned rotation: a later {@code v2} could carry a key id and let
 * {@link #decrypt} pick the right key, so old ciphertext stays readable. A fresh random 12-byte
 * IV (from {@link SecureRandom}) is generated per call, so encrypting the same input twice yields
 * different tokens.
 * <p>
 * <strong>Legacy passthrough.</strong> This app went live storing VPAs in plaintext, so
 * {@link #decrypt} treats any value <em>without</em> the {@code "enc:v1:"} prefix as legacy
 * plaintext and returns it unchanged. Because {@link #encrypt} always produces the prefixed form,
 * a legacy row is transparently upgraded to ciphertext the next time it is saved.
 * <p>
 * Null/blank-safe: {@code encrypt(null) == null}, {@code decrypt(null) == null}. Never logs the
 * key or any plaintext.
 */
@Component
public class AesGcmEncryptor {

    /** Marks our own ciphertext. Anything not starting with this is treated as legacy plaintext. */
    static final String PREFIX = "enc:v1:";

    private static final int IV_LENGTH_BYTES = 12;      // 96-bit nonce — the GCM standard/optimal size
    private static final int GCM_TAG_LENGTH_BITS = 128; // full 16-byte auth tag
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEncryptor(
            // DEV-ONLY default: base64 of a fixed, well-known 32-byte value ("dev-only-payout-
            // key-not-for-prod"). It is committed to source control, so it is NOT a secret — it
            // only keeps local/dev/test working with no config. Production MUST override it via
            // PAYOUT_ENC_KEY (see application.yml); ProdSecretGuard fails startup under the prod
            // profile if this default is still in place.
            @Value("${app.crypto.payout-key:ZGV2LW9ubHktcGF5b3V0LWtleS1ub3QtZm9yLXByb2Q=}")
            String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "app.crypto.payout-key must be a base64-encoded 32-byte key for AES-256; decoded length was "
                            + keyBytes.length + " bytes.");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    /** Encrypts to the self-describing {@code "enc:v1:..."} token. Null/blank in -> same value out. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherAndTag = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherAndTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherAndTag, 0, combined, iv.length, cipherAndTag.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // Deliberately opaque: never echo the plaintext or key material into the message.
            throw new IllegalStateException("Failed to encrypt payout field", e);
        }
    }

    /**
     * Decrypts a stored value. A value without the {@code "enc:v1:"} prefix is legacy plaintext and
     * is returned unchanged. Null in -> null out.
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            // Legacy plaintext row written before encryption was introduced — pass through as-is.
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            byte[] cipherAndTag = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, cipherAndTag, 0, cipherAndTag.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(cipherAndTag);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt payout field", e);
        }
    }
}
