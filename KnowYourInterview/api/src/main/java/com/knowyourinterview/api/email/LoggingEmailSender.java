package com.knowyourinterview.api.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The no-SMTP-configured fallback: writes the message to the log instead of sending it.
 * <p>
 * Same graceful-degradation pattern the app already uses for Razorpay, Google Sign-In and
 * Sentry — an unset integration must not stop the app from starting or break unrelated flows.
 * A developer running locally with no mail credentials still gets a working confirmation and
 * password-reset flow; they read the link out of the console, which is exactly how password
 * reset worked before any of this existed.
 * <p>
 * It logs the plain-text body specifically (not the HTML), because the text alternative is
 * the one that's readable in a terminal — the whole point here is that a human can copy the
 * link out of it.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        log.info("""
                [email not sent — no SMTP configured, set spring.mail.host to enable]
                  To:      {}
                  Subject: {}
                {}""", to, subject, textBody);
    }
}
