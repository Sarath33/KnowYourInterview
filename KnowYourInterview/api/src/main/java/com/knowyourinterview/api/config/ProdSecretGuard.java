package com.knowyourinterview.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard: under the "prod" profile, refuse to start if any secret that has a committed
 * local-dev default is still set to that default — the JWT signing secret and the payout-field
 * encryption key. Signing production tokens with a value that is in source control would let
 * anyone forge admin tokens; encrypting payout VPAs with a committed key is no encryption at all.
 * No-op under any other profile — the bean only exists when "prod" is active (see @Profile), so
 * local/dev/test startup is unaffected.
 */
@Component
@Profile("prod")
public class ProdSecretGuard implements ApplicationRunner {

    private static final String DEV_DEFAULT_JWT_SECRET =
            "dev-only-change-me-0123456789abcdef0123456789abcdef";

    // Must match the DEV-ONLY default baked into AesGcmEncryptor's @Value fallback.
    private static final String DEV_DEFAULT_PAYOUT_KEY =
            "ZGV2LW9ubHktcGF5b3V0LWtleS1ub3QtZm9yLXByb2Q=";

    private final String jwtSecret;
    private final String payoutEncKey;

    public ProdSecretGuard(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.crypto.payout-key}") String payoutEncKey) {
        this.jwtSecret = jwtSecret;
        this.payoutEncKey = payoutEncKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (DEV_DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "app.jwt.secret is still the local-dev default while running under the 'prod' profile — "
                            + "set the JWT_SECRET environment variable to a real, secret value before deploying.");
        }
        if (DEV_DEFAULT_PAYOUT_KEY.equals(payoutEncKey)) {
            throw new IllegalStateException(
                    "app.crypto.payout-key is still the local-dev default while running under the 'prod' profile — "
                            + "set the PAYOUT_ENC_KEY environment variable to a real, secret base64 32-byte key "
                            + "before deploying.");
        }
    }
}
