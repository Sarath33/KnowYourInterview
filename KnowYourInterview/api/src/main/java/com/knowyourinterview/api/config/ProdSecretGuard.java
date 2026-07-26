package com.knowyourinterview.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard: under the "prod" profile, refuse to start if the JWT signing secret is
 * still the local-dev default. Signing production tokens with a value that is committed to
 * source control would let anyone forge admin tokens. No-op under any other profile — the
 * bean only exists when "prod" is active (see @Profile), so local/dev/test startup is
 * unaffected.
 */
@Component
@Profile("prod")
public class ProdSecretGuard implements ApplicationRunner {

    private static final String DEV_DEFAULT_JWT_SECRET =
            "dev-only-change-me-0123456789abcdef0123456789abcdef";

    private final String jwtSecret;

    public ProdSecretGuard(@Value("${app.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (DEV_DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "app.jwt.secret is still the local-dev default while running under the 'prod' profile — "
                            + "set the JWT_SECRET environment variable to a real, secret value before deploying.");
        }
    }
}
