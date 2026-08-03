package com.knowyourinterview.api.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Picks the {@link EmailSender} implementation based on what's actually configured, in this
 * priority order: Resend's HTTP API, then SMTP, then the logging fallback.
 * <p>
 * The Resend-API path exists specifically because outbound SMTP turned out to be blocked on
 * Railway — confirmed in production by both port 587 and 465 connect attempts to
 * {@code smtp.resend.com} timing out at the socket level (see {@link ResendApiEmailSender}'s
 * Javadoc). {@code RESEND_API_KEY} therefore takes priority over any SMTP config: on a host
 * that blocks SMTP egress, leaving both set and letting SMTP "win" would silently keep failing.
 * <p>
 * The SMTP decision below it is driven by the <em>value</em> of {@code spring.mail.host}, not
 * by whether a {@link JavaMailSender} bean exists. That distinction matters:
 * {@code application.yml} declares {@code host: ${MAIL_HOST:}}, so with the env var unset the
 * property is <em>present but empty</em> — and Spring Boot's {@code MailSenderAutoConfiguration}
 * is gated on {@code @ConditionalOnProperty(prefix = "spring.mail", name = "host")}, which
 * matches any present non-"false" value including the empty string. Keying off bean presence
 * would therefore hand back an SMTP sender pointed at nowhere, and every message would fail
 * (quietly, since sends don't throw) instead of falling back to the log. Reading the host
 * directly can't drift from what's really configured.
 * <p>
 * An explicit factory method rather than {@code @ConditionalOnBean} /
 * {@code @ConditionalOnMissingBean} on the implementations: those are evaluated in
 * bean-registration order and are order-sensitive between user config and auto-configuration,
 * which is exactly this situation. This also logs which mode is active — worth having, since
 * "why didn't the email arrive" otherwise has a silent answer.
 */
@Configuration
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    public EmailSender emailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${app.email.resend-api-key:}") String resendApiKey,
            @Value("${app.email.from-address:no-reply@knowyourinterview.local}") String fromAddress,
            @Value("${app.email.from-name:Know Your Interview}") String fromName) {

        if (!resendApiKey.isBlank()) {
            log.info("Resend API key configured — transactional email will be sent via Resend's HTTP "
                    + "API from {}. Takes priority over any SMTP config, since Railway blocks outbound "
                    + "SMTP but not HTTPS.", fromAddress);
            return new ResendApiEmailSender(resendApiKey, fromAddress, fromName);
        }

        JavaMailSender mailSender = mailHost.isBlank() ? null : mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.info("No Resend API key or SMTP host configured (RESEND_API_KEY / MAIL_HOST) — "
                    + "confirmation and password-reset emails will be written to the log instead of "
                    + "sent. Set RESEND_API_KEY (recommended on Railway — see docs/06-deployment.md) "
                    + "or the MAIL_* SMTP properties to enable delivery.");
            return new LoggingEmailSender();
        }
        log.info("SMTP configured ({}) — transactional email will be sent from {}", mailHost, fromAddress);
        return new SmtpEmailSender(mailSender, fromAddress, fromName);
    }
}
