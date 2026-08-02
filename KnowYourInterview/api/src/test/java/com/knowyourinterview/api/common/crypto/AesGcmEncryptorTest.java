package com.knowyourinterview.api.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for AesGcmEncryptor — no Spring context. Constructs the encryptor with the
 * same base64 32-byte DEV key the app defaults to.
 */
class AesGcmEncryptorTest {

    // base64 of the fixed 32-byte dev value "dev-only-payout-key-not-for-prod".
    private static final String DEV_KEY = "ZGV2LW9ubHktcGF5b3V0LWtleS1ub3QtZm9yLXByb2Q=";

    private final AesGcmEncryptor encryptor = new AesGcmEncryptor(DEV_KEY);

    @Test
    void encryptThenDecryptRoundTripsToTheOriginal() {
        String plaintext = "jane.doe@okhdfcbank";

        String token = encryptor.encrypt(plaintext);

        assertThat(token).startsWith("enc:v1:");
        assertThat(token).doesNotContain(plaintext);
        assertThat(encryptor.decrypt(token)).isEqualTo(plaintext);
    }

    @Test
    void encryptingTheSameInputTwiceYieldsDifferentTokens() {
        String plaintext = "jane@upi";

        String first = encryptor.encrypt(plaintext);
        String second = encryptor.encrypt(plaintext);

        // Fresh random IV per call, so the tokens differ even though the plaintext is identical...
        assertThat(first).isNotEqualTo(second);
        // ...and both still decrypt back to the same value.
        assertThat(encryptor.decrypt(first)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void decryptOfNonPrefixedLegacyPlaintextReturnsItUnchanged() {
        // A row written before encryption existed — no "enc:v1:" prefix, so it passes through.
        String legacy = "legacy@upi";

        assertThat(encryptor.decrypt(legacy)).isEqualTo(legacy);
    }

    @Test
    void isNullAndBlankSafe() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
        assertThat(encryptor.encrypt("")).isEqualTo("");
        assertThat(encryptor.encrypt("   ")).isEqualTo("   ");
    }
}
