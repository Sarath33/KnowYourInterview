package com.knowyourinterview.api.auth;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Real GoogleIdTokenVerifierPort implementation, backed by Google's own client library.
 * GoogleIdTokenVerifier checks the token's signature against Google's published JWKS, plus
 * issuer/audience/expiry — no network round-trip per login (keys are fetched lazily and
 * cached). If app.google.client-id (GOOGLE_CLIENT_ID) is blank, this quietly disables itself
 * rather than failing at startup — same graceful-degradation pattern used for Razorpay/Sentry.
 */
@Component
public class GoogleSignInVerifier implements GoogleIdTokenVerifierPort {

    private final GoogleIdTokenVerifier delegate;

    public GoogleSignInVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.delegate = clientId.isBlank()
                ? null
                : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(Collections.singletonList(clientId))
                        .build();
    }

    @Override
    public GoogleUserInfo verify(String idTokenString) {
        if (delegate == null) {
            throw new GoogleAuthNotConfiguredException();
        }

        GoogleIdToken idToken;
        try {
            idToken = delegate.verify(idTokenString);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new InvalidCredentialsException();
        }
        if (idToken == null) {
            throw new InvalidCredentialsException();
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        // Google lets an account exist with an unverified email (e.g. a non-Gmail address
        // added but never confirmed) — refuse to trust it as a login identity in that case.
        if (email == null || !Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new InvalidCredentialsException();
        }
        Object nameClaim = payload.get("name");
        String name = (nameClaim instanceof String s && !s.isBlank()) ? s : email;

        return new GoogleUserInfo(payload.getSubject(), email, name);
    }
}
