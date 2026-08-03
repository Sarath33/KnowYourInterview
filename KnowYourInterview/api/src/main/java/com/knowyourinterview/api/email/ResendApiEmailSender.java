package com.knowyourinterview.api.email;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends via Resend's HTTP API ({@code POST https://api.resend.com/emails}) instead of SMTP.
 * <p>
 * Exists specifically because Railway blocks outbound SMTP: in production, connections to
 * {@code smtp.resend.com} on both 587 and 465 timed out (confirmed via deploy logs — a
 * {@code SocketTimeoutException} on the socket connect itself, before TLS/auth ever happens),
 * which is Railway's anti-abuse egress policy, not a config mistake. The HTTP API sends over
 * 443, which is never blocked, at the cost of provider lock-in: unlike {@link SmtpEmailSender}
 * this only works with Resend. See {@link EmailConfig} for how the two senders are chosen
 * between — this one takes priority when {@code RESEND_API_KEY} is set, so an SMTP config left
 * in place from before doesn't silently keep failing.
 * <p>
 * Failures are logged, never thrown — same contract {@link EmailSender} requires and
 * {@link SmtpEmailSender} already follows: callers are auth flows that have already committed
 * the operation (account created, token row written), which a bounced email must not undo.
 */
public class ResendApiEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendApiEmailSender.class);
    private static final URI ENDPOINT = URI.create("https://api.resend.com/emails");

    private final HttpClient httpClient;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;

    public ResendApiEmailSender(String apiKey, String fromAddress, String fromName) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        // Same 5s connect budget SmtpEmailSender's SMTP properties use elsewhere — sending is
        // synchronous within the request thread, so this bounds the worst case a caller sees.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        String from = (fromName != null && !fromName.isBlank())
                ? fromName + " <" + fromAddress + ">"
                : fromAddress;
        try {
            // Hand-built rather than via Jackson's ObjectMapper: Spring Boot 4 moved Jackson's
            // databind module to Jackson 3 (package `tools.jackson.databind`, not the classic
            // `com.fasterxml.jackson.databind` — only jackson-annotations kept its old package,
            // which is why that one's still used elsewhere in this codebase). Five known-flat
            // string fields don't need a JSON library either way, and this sidesteps being
            // coupled to whichever Jackson major version happens to be on the classpath.
            String body = "{"
                    + "\"from\":" + jsonString(from) + ","
                    + "\"to\":[" + jsonString(to) + "],"
                    + "\"subject\":" + jsonString(subject) + ","
                    + "\"html\":" + jsonString(htmlBody) + ","
                    + "\"text\":" + jsonString(textBody)
                    + "}";

            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Resend's error response is small (a JSON {message, name} object) and doesn't
                // echo the recipient back, so logging it whole alongside `to` is safe and is
                // the only way to tell an auth failure apart from a rejected/unverified sender.
                log.error("Failed to send \"{}\" email to {} — Resend returned {}: {}",
                        subject, to, response.statusCode(), response.body());
            } else {
                // Deliberately doesn't log the recipient at INFO — see SmtpEmailSender for why.
                log.debug("Sent \"{}\" email", subject);
            }
        } catch (Exception e) {
            log.error("Failed to send \"{}\" email to {}", subject, to, e);
        }
    }

    /**
     * Minimal JSON string-literal encoder — quotes and escapes exactly what
     * {@link com.knowyourinterview.api.email.AuthEmails}'s generated subjects/bodies can
     * actually contain (arbitrary text, but no embedded JSON structure), which is all this
     * needs. Not a general-purpose JSON writer; see the comment in {@link #send} for why one
     * isn't used here.
     */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
