package com.knowyourinterview.api.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which sender gets chosen, and specifically that an unconfigured host falls back to logging.
 * <p>
 * Worth its own test because the obvious implementation — "is there a JavaMailSender bean?" —
 * is wrong here. application.yml declares {@code host: ${MAIL_HOST:}}, so with no env var the
 * property is present but empty, and Spring Boot's mail auto-configuration is gated on the
 * property merely being present. Bean presence would therefore say "SMTP is configured" on a
 * machine with no mail credentials at all, and every message would vanish into a failed send
 * instead of appearing in the console where a developer can click the link.
 */
class EmailConfigTest {

    private final EmailConfig config = new EmailConfig();

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    @Test
    void fallsBackToLoggingWhenNoHostOrResendKeyIsConfigured() {
        EmailSender sender = config.emailSender(providerOf(null), "", "", "no-reply@example.com", "KYI");

        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    /** The case that motivated reading the host rather than the bean: auto-configuration has
     * produced a JavaMailSender (because the property is present), but it points at nothing. */
    @Test
    void fallsBackToLoggingWhenTheHostIsBlankEvenIfAMailSenderBeanExists() {
        EmailSender sender = config.emailSender(
                providerOf(mock(JavaMailSender.class)), "   ", "", "no-reply@example.com", "KYI");

        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void usesSmtpWhenAHostAndMailSenderAreBothPresentAndNoResendKeyIsSet() {
        EmailSender sender = config.emailSender(
                providerOf(mock(JavaMailSender.class)), "smtp.example.com", "", "no-reply@example.com", "KYI");

        assertThat(sender).isInstanceOf(SmtpEmailSender.class);
    }

    /** Resend takes priority over SMTP even when both are configured — see EmailConfig's
     * Javadoc for why (Railway blocks outbound SMTP, so SMTP "winning" would silently fail). */
    @Test
    void usesResendApiWhenResendKeyIsSetEvenIfSmtpIsAlsoConfigured() {
        EmailSender sender = config.emailSender(
                providerOf(mock(JavaMailSender.class)), "smtp.example.com", "re_test_key",
                "no-reply@example.com", "KYI");

        assertThat(sender).isInstanceOf(ResendApiEmailSender.class);
    }

    @Test
    void usesResendApiWhenOnlyResendKeyIsConfigured() {
        EmailSender sender = config.emailSender(providerOf(null), "", "re_test_key", "no-reply@example.com", "KYI");

        assertThat(sender).isInstanceOf(ResendApiEmailSender.class);
    }
}
