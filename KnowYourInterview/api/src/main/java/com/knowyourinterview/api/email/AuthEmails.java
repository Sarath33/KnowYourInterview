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

    /**
     * The registration confirmation code.
     * <p>
     * The code goes in the subject line as well as the body, so it's readable from a
     * notification or an inbox list without opening the message — which is most of the point of
     * a code over a link, given the usual case is reading it on a phone and typing it somewhere
     * else. Everything else stays deliberately plain: no link to click, nothing to get wrong.
     */
    public static Message confirmEmail(String displayName, String code, long ttlMinutes) {
        String subject = code + " is your Know Your Interview confirmation code";
        String expiry = ttlMinutes == 1 ? "1 minute" : ttlMinutes + " minutes";

        String html = wrap(
                "Confirm your email",
                greeting(displayName)
                        + paragraph("Thanks for signing up. Enter this code in the app to start submitting "
                                + "interview experiences and unlocking other people's.")
                        + code(code)
                        + paragraph(small("The code expires in " + expiry + " and can only be used once."))
                        + paragraph(small("If you didn't create this account, you can ignore this email — "
                                + "nothing happens until the code is entered.")));

        String text = greetingText(displayName)
                + "Thanks for signing up. Enter this code in the app to start submitting\n"
                + "interview experiences and unlocking other people's:\n\n"
                + "    " + code + "\n\n"
                + "The code expires in " + expiry + " and can only be used once.\n\n"
                + "If you didn't create this account, you can ignore this email — nothing\n"
                + "happens until the code is entered.\n";

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

    /** The code itself, set large and letter-spaced so it's legible at a glance on a phone.
     * Monospace matters more than it looks: in a proportional font, digits like 1 and 7 and a
     * run of identical characters are easy to misread when retyping. */
    private static String code(String value) {
        return ("<p style=\"margin:0 0 20px;\"><span style=\"display:inline-block;"
                + "font-family:'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace;"
                + "font-size:32px;font-weight:700;letter-spacing:0.18em;color:#1f2430;"
                + "background:#f6f7f9;border:1px solid #e4e7ec;border-radius:10px;"
                + "padding:14px 24px;\">%s</span></p>").formatted(escape(value));
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
