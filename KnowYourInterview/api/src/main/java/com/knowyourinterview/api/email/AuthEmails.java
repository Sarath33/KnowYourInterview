package com.knowyourinterview.api.email;

/**
 * The two transactional messages the app sends, as plain content builders — no template
 * engine, no resource files.
 * <p>
 * Two short emails don't justify pulling in Thymeleaf and a template loader; when there's a
 * third or a fourth, or when someone non-technical needs to edit the copy without a
 * redeploy, that's the moment to move them out. Keeping them in one class rather than inline
 * at the call sites means the wording and the styling stay consistent between them, and
 * they're testable without a mail server.
 * <p>
 * The HTML is intentionally crude — inline styles, table-free, no images, no web fonts. Email
 * clients are not browsers: Outlook renders with Word's engine, Gmail strips {@code <style>}
 * blocks, and anything clever degrades unpredictably. The plain-text alternative carries the
 * same link, so a client that renders none of this still works.
 */
public final class AuthEmails {

    private AuthEmails() {
    }

    public record Message(String subject, String htmlBody, String textBody) {}

    public static Message confirmEmail(String displayName, String confirmUrl) {
        String subject = "Confirm your email — Know Your Interview";

        String html = wrap(
                "Confirm your email",
                greeting(displayName)
                        + paragraph("Thanks for signing up. Confirm this address to start submitting "
                                + "interview experiences and unlocking other people's.")
                        + button(confirmUrl, "Confirm my email")
                        + paragraph(small("This link works once and expires in 24 hours. "
                                + "If the button doesn't work, paste this into your browser:"))
                        + paragraph(small(escape(confirmUrl)))
                        + paragraph(small("If you didn't create this account, you can ignore this email — "
                                + "nothing happens until the link is used.")));

        String text = greetingText(displayName)
                + "Thanks for signing up. Confirm this address to start submitting interview\n"
                + "experiences and unlocking other people's:\n\n"
                + confirmUrl + "\n\n"
                + "This link works once and expires in 24 hours.\n\n"
                + "If you didn't create this account, you can ignore this email — nothing\n"
                + "happens until the link is used.\n";

        return new Message(subject, html, text);
    }

    public static Message passwordReset(String displayName, String resetUrl) {
        String subject = "Reset your password — Know Your Interview";

        String html = wrap(
                "Reset your password",
                greeting(displayName)
                        + paragraph("Someone asked to reset the password for this account. "
                                + "If it was you, pick a new one here:")
                        + button(resetUrl, "Set a new password")
                        + paragraph(small("This link works once and expires in an hour. "
                                + "If the button doesn't work, paste this into your browser:"))
                        + paragraph(small(escape(resetUrl)))
                        + paragraph(small("If it wasn't you, ignore this email — your password hasn't "
                                + "changed and nothing has happened to your account.")));

        String text = greetingText(displayName)
                + "Someone asked to reset the password for this account. If it was you, pick a\n"
                + "new one here:\n\n"
                + resetUrl + "\n\n"
                + "This link works once and expires in an hour.\n\n"
                + "If it wasn't you, ignore this email — your password hasn't changed and\n"
                + "nothing has happened to your account.\n";

        return new Message(subject, html, text);
    }

    // --- building blocks -------------------------------------------------------------

    private static String wrap(String heading, String body) {
        return """
                <!doctype html>
                <html><body style="margin:0;padding:24px;background:#f6f7f9;\
                font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;\
                color:#1f2430;">
                  <div style="max-width:520px;margin:0 auto;background:#ffffff;border:1px solid #e4e7ec;\
                border-radius:12px;padding:32px;">
                    <div style="font-size:13px;font-weight:600;letter-spacing:0.02em;\
                text-transform:uppercase;color:#3b5bdb;margin-bottom:8px;">Know Your Interview</div>
                    <h1 style="font-size:22px;margin:0 0 18px;">%s</h1>
                    %s
                  </div>
                </body></html>"""
                .formatted(escape(heading), body);
    }

    /** Falls back to a name-free greeting rather than printing "Hi null" or "Hi ,". */
    private static String greeting(String displayName) {
        return (displayName == null || displayName.isBlank())
                ? paragraph("Hi,")
                : paragraph("Hi " + escape(displayName) + ",");
    }

    private static String greetingText(String displayName) {
        return (displayName == null || displayName.isBlank()) ? "Hi,\n\n" : "Hi " + displayName + ",\n\n";
    }

    private static String paragraph(String content) {
        return "<p style=\"font-size:15px;line-height:1.6;color:#414a5c;margin:0 0 16px;\">" + content + "</p>";
    }

    private static String small(String content) {
        return "<span style=\"font-size:13px;color:#6b7280;word-break:break-all;\">" + content + "</span>";
    }

    private static String button(String url, String label) {
        return ("<p style=\"margin:0 0 20px;\"><a href=\"%s\" "
                + "style=\"display:inline-block;background:#3b5bdb;color:#ffffff;text-decoration:none;"
                + "font-weight:700;font-size:15px;padding:12px 22px;border-radius:8px;\">%s</a></p>")
                .formatted(escape(url), escape(label));
    }

    /**
     * Minimal HTML escaping for the values interpolated above.
     * <p>
     * displayName is user-controlled and goes into an email body — unescaped, a display name
     * containing markup would be rendered by the recipient's client. The URLs are built by
     * the app, but escaping them too costs nothing and means no future caller has to
     * remember which arguments are trusted.
     */
    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
