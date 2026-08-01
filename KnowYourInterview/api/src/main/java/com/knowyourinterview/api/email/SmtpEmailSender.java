package com.knowyourinterview.api.email;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends over SMTP via Spring's {@link JavaMailSender}.
 * <p>
 * SMTP rather than a vendor's HTTP API on purpose: Postmark, SendGrid, Resend, Mailgun and
 * everything else all speak it, so choosing (or switching) providers is four config values in
 * {@code application.yml} and no code change at all. The cost is slightly less observability
 * than a REST API gives — no per-message id in the response — which the provider's own
 * dashboard covers.
 * <p>
 * Failures are logged, never thrown. See {@link EmailSender} for why that's the contract
 * rather than an oversight: every caller here is an auth flow where the surrounding operation
 * has already succeeded (an account exists, a token row is committed) and must not be undone
 * by a mail server hiccup. The visible consequence of a swallowed failure is a user who
 * doesn't receive a link and uses "resend" — recoverable — whereas the alternative is a
 * registration that 500s after the account was created.
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public SmtpEmailSender(JavaMailSender mailSender, String fromAddress, String fromName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true so the text alternative actually gets attached — a
            // single-part HTML message is more likely to be filtered, and unreadable in
            // the handful of clients that still don't render HTML.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            if (fromName != null && !fromName.isBlank()) {
                helper.setFrom(fromAddress, fromName);
            } else {
                helper.setFrom(fromAddress);
            }
            mailSender.send(message);
            // Deliberately doesn't log the recipient at INFO — the address is personal data
            // and this line would otherwise put a copy of every user's email into the log
            // aggregator on every send.
            log.debug("Sent \"{}\" email", subject);
        } catch (MessagingException | MailException | java.io.UnsupportedEncodingException e) {
            // The address IS logged here: a delivery failure is unactionable without knowing
            // who it was for, and this path is rare rather than every-send.
            log.error("Failed to send \"{}\" email to {}", subject, to, e);
        }
    }
}
