package com.knowyourinterview.api.auth;

/**
 * Minimal, SDK-independent shape of the identity claims we trust from a verified Google ID
 * token — keeps AuthService's business logic decoupled from Google's client library types.
 * See GoogleIdTokenVerifierPort.
 */
public record GoogleUserInfo(String subject, String email, String name) {
}
