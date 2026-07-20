package com.knowyourinterview.api.auth;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("An account with email " + email + " already exists");
    }
}
