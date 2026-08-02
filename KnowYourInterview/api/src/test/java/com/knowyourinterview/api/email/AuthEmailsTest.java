package com.knowyourinterview.api.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthEmailsTest {

    private static final String RESET_URL = "https://app.example.com/reset-password?token=abc123";

    /** Both bodies have to carry the code — the plain-text alternative isn't decoration, it's
     * what a client that doesn't render HTML (and what a terminal, when LoggingEmailSender is
     * in play) actually shows. */
    @Test
    void confirmationCarriesTheCodeInBothBodies() {
        AuthEmails.Message message = AuthEmails.confirmEmail("Jane", "481902", 10);

        assertThat(message.htmlBody()).contains("481902");
        assertThat(message.textBody()).contains("481902");
        assertThat(message.textBody()).doesNotContain("<");
    }

    /** In the subject too, so it's readable from a notification or an inbox list without
     * opening the message — most of the point of a code over a link. */
    @Test
    void confirmationPutsTheCodeInTheSubjectLine() {
        AuthEmails.Message message = AuthEmails.confirmEmail("Jane", "481902", 10);

        assertThat(message.subject()).startsWith("481902");
    }

    @Test
    void confirmationStatesHowLongTheCodeLasts() {
        assertThat(AuthEmails.confirmEmail("Jane", "481902", 10).textBody()).contains("10 minutes");
        // Singular, not "1 minutes".
        assertThat(AuthEmails.confirmEmail("Jane", "481902", 1).textBody()).contains("1 minute");
    }

    /** Nothing to click: the whole point of replacing the link was one redemption path. */
    @Test
    void confirmationContainsNoLink() {
        AuthEmails.Message message = AuthEmails.confirmEmail("Jane", "481902", 10);

        assertThat(message.htmlBody()).doesNotContain("<a href");
        assertThat(message.textBody()).doesNotContain("http");
    }

    @Test
    void passwordResetCarriesTheLinkInBothBodies() {
        AuthEmails.Message message = AuthEmails.passwordReset("Jane", RESET_URL);

        assertThat(message.subject()).contains("Reset");
        assertThat(message.htmlBody()).contains(RESET_URL);
        assertThat(message.textBody()).contains(RESET_URL);
    }

    /** displayName is user-controlled and lands in an HTML email body. Unescaped, a display
     * name containing markup would be rendered by the recipient's mail client. */
    @Test
    void escapesAUserControlledDisplayNameInTheHtmlBody() {
        AuthEmails.Message message = AuthEmails.confirmEmail("<script>alert(1)</script>", "481902", 10);

        assertThat(message.htmlBody()).doesNotContain("<script>");
        assertThat(message.htmlBody()).contains("&lt;script&gt;");
    }

    /** A blank or missing name shouldn't produce "Hi ," or "Hi null," — Google signups can
     * legitimately arrive without one. */
    @Test
    void greetsWithoutANameWhenThereIsntOne() {
        assertThat(AuthEmails.confirmEmail(null, "481902", 10).textBody()).startsWith("Hi,");
        assertThat(AuthEmails.confirmEmail("   ", "481902", 10).textBody()).startsWith("Hi,");
        assertThat(AuthEmails.confirmEmail(null, "481902", 10).htmlBody()).doesNotContain("Hi null");
    }
}
