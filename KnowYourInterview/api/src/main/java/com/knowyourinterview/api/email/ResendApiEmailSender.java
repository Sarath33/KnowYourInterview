package com.knowyourinterview.api.email;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper objectMapper;
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
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        String from = (fromName != null && !fromName.isBlank())
                ? fromName + " <" + fromAddress + ">"
                : fromAddress;
        try {
            Map<String, Object> payload = Map.of(
                    "from", from,
                    "to", List.of(to),
                    "subject", subject,
                    "html", htmlBody,
                    "text", textBody);
            String body = objectMapper.writeValueAsString(payload);

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
}
