package com.knowyourinterview.api.functional.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.knowyourinterview.api.auth.GoogleIdTokenVerifierPort;

/**
 * Test-only beans shared by every functional test. Kept to the absolute minimum: the more the
 * functional suite replaces, the less of the real application it actually proves.
 *
 * <p>The single substitution is {@link GoogleIdTokenVerifierPort} — see
 * {@link StubGoogleIdTokenVerifier} for why, and {@code docs/09-test-plan.md} §6.4 for what that
 * costs in coverage. {@code @Primary} rather than a bean-name override, because the real
 * {@code GoogleSignInVerifier} is a {@code @Component} and will still be constructed (harmlessly:
 * with a blank client id it just sets its delegate to null); {@code @Primary} decides which one
 * {@code AuthService} is injected with.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FunctionalTestConfig {

    @Bean
    @Primary
    GoogleIdTokenVerifierPort stubGoogleIdTokenVerifier() {
        return new StubGoogleIdTokenVerifier();
    }
}
