package com.knowyourinterview.api.payment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import org.json.JSONObject;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Backup confirmation path — see PurchaseService for why this exists alongside the
 * client-confirm flow. Publicly reachable (see SecurityConfig), so signature
 * verification IS the auth here, not a JWT.
 *
 * Deliberately reads the raw request body via HttpServletRequest rather than
 * @RequestBody String: with Jackson on the classpath, Spring would otherwise try to
 * JSON-deserialize the body straight into a String and fail (a JSON object isn't a
 * valid JSON string literal), before signature verification ever gets a chance to run.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final PurchaseService purchaseService;

    public WebhookController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    @PostMapping("/api/v1/payments/webhook")
    public ResponseEntity<Void> handleWebhook(
            HttpServletRequest request,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) throws IOException {
        String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);

        if (signature == null || !purchaseService.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Rejected a webhook call with an invalid or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        JSONObject payload = new JSONObject(rawBody);
        String event = payload.optString("event", "");

        if ("payment.captured".equals(event)) {
            JSONObject payment = payload
                    .getJSONObject("payload")
                    .getJSONObject("payment")
                    .getJSONObject("entity");
            purchaseService.handlePaymentCaptured(payment.getString("order_id"), payment.getString("id"));
        } else {
            log.debug("Ignoring webhook event: {}", event);
        }

        return ResponseEntity.ok().build();
    }
}
