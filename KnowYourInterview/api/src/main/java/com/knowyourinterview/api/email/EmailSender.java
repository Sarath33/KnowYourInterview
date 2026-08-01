package com.knowyourinterview.api.email;

/**
 * Everything the app needs from an email provider, which is very little: send one
 * transactional message to one recipient.
 * <p>
 * Kept deliberately thin. There are no templates, no attachments, no bulk sends and no
 * scheduling here, because nothing sends anything more elaborate than a link — a confirmation
 * and a password reset. Widening this interface ahead of a real second use case would be
 * guessing at requirements.
 * <p>
 * Implementations must not throw for ordinary delivery failures. Callers are auth flows where
 * a bounced email must not fail the operation that triggered it: a registration that succeeded
 * shouldn't roll back because a mail server was briefly unreachable, and a forgot-password
 * request must return the same generic response whether or not anything was sent (otherwise
 * the response becomes a user-enumeration oracle). Log and move on instead.
 */
public interface EmailSender {

    /**
     * @param to        recipient address
     * @param subject   plain-text subject line
     * @param htmlBody  HTML body, for clients that render it
     * @param textBody  plain-text alternative — not optional. Some clients and most
     *                  spam filters expect a multipart message, and a text part measurably
     *                  helps deliverability for exactly the kind of link-bearing mail this
     *                  sends.
     */
    void send(String to, String subject, String htmlBody, String textBody);
}
