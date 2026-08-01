package com.knowyourinterview.api.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Picks the {@link EmailSender} implementation based on whether SMTP is actually configured.
 * <p>
 * Spring Boot's mail auto-configuration only creates a {@link JavaMailSender} when
 * {@code spring.mail.host} is set, so the presence of that bean is a reliable signal for
 * "credentials exist" — no separate enabled/disabled flag to keep in sync with the config
 * that actually matters. {@link ObjectProvider} is what lets this ask without requiring the
 * bean to exist.
 * <p>
 * Chosen over annotating the two implementations with {@code @ConditionalOnBean} /
 * {@code @ConditionalOnMissingBean}: those conditions are evaluated in bean-registration
 * order and are notoriously order-sensitive between user configuration and auto-configuration,
 * which is exactly the situation here. An explicit factory method has no such subtlety, and
 * it logs which mode is active — worth having, since "why didn't the email arrive" otherwise
 * has a silent answer.
 */
@Configuration
public class EmailConfig {

    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    public EmailSender emailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.email.from-address:no-reply@knowyourinterview.local}") String fromAddress,
            @Value("${app.email.from-name:Know Your Interview}") String fromName) {

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.info("No SMTP host configured (spring.mail.host) — confirmation and password-reset "
                    + "emails will be written to the log instead of sent. Set the mail properties to enable delivery.");
            return new LoggingEmailSender();
        }
        log.info("SMTP configured — transactional email will be sent from {}", fromAddress);
        return new SmtpEmailSender(mailSender, fromAddress, fromName);
    }
}
