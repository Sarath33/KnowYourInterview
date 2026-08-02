package com.knowyourinterview.api.functional;

import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-HOOK — the Razorpay webhook, the backup path that grants access when the buyer's browser
 * tab closed before it could confirm. See {@code docs/09-test-plan.md} §7.11.
 *
 * <p>This endpoint is unauthenticated by necessity: Razorpay's servers call it, not a logged-in
 * browser, so {@code SecurityConfig} permits it and the {@code X-Razorpay-Signature} check inside
 * the controller <em>is</em> the authentication. That makes it the one publicly reachable route
 * that can grant paid access, and the signature verification the only thing standing in front of
 * it. Every negative case below is therefore a P0.
 *
 * <p>Signatures are computed over the exact bytes sent. {@link #exchange} passes a String body
 * through untouched, so the payload signed here is the payload the server hashes — no message
 * converter gets a chance to reformat it in between.
 */
class WebhookFunctionalIT extends FunctionalTestBase {

    private ResponseEntity<String> callWebhook(String rawBody, String signature) {
        HttpHeaders headers = headers(null);
        if (signature != null) {
            headers.set("X-Razorpay-Signature", signature);
        }
        return exchange(HttpMethod.POST, "/api/v1/payments/webhook", headers, rawBody);
    }

    /** A published experience with a CREATED purchase waiting on it. */
    private String pendingOrderFor(Actor buyer) {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);
        return createdPurchase(buyer, experienceId);
    }

    // --- Reachability and signature verification ------------------------------------------------

    @Test
    @DisplayName("FT-HOOK-01/02: the endpoint is reachable without a JWT but refuses an unsigned call")
    void reachableWithoutAJwtButRefusesUnsignedCalls() {
        String body = Payloads.paymentCaptured("order_x", "pay_x").toString();

        ResponseEntity<String> unsigned = callWebhook(body, null);

        // 401 from the controller's own signature check — not from Spring Security, which
        // permits this path. The distinction matters: if this ever became a security-filter 401,
        // Razorpay's calls would be rejected before the signature was ever considered.
        assertThat(statusOf(unsigned)).isEqualTo(401);
    }

    @Test
    @DisplayName("FT-HOOK-03: a wrong signature is refused and grants nothing")
    void wrongSignatureIsRefused() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);
        String body = Payloads.paymentCaptured(orderId, Payloads.razorpayPaymentId()).toString();

        ResponseEntity<String> response = callWebhook(body, "0000000000000000000000000000000000000000");

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(countRows("entitlements")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("FT-HOOK-04: a signature valid for one payload is refused with another")
    void signatureIsBoundToItsPayload() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);
        String honestBody = Payloads.paymentCaptured("order_someone_elses", "pay_1").toString();
        String signatureForHonestBody = webhookSignature(honestBody);

        // Same signature, tampered body pointing at the attacker's own unpaid order. If the HMAC
        // didn't cover the payload, this is a free unlock for anyone who ever saw one webhook.
        String tamperedBody = Payloads.paymentCaptured(orderId, "pay_1").toString();
        ResponseEntity<String> response = callWebhook(tamperedBody, signatureForHonestBody);

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(countRows("entitlements")).isZero();
    }

    // --- The happy path ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-HOOK-05/11: a correctly signed payment.captured grants the entitlement")
    void validPaymentCapturedGrantsEntitlement() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);
        String paymentId = Payloads.razorpayPaymentId();
        String body = Payloads.paymentCaptured(orderId, paymentId).toString();

        ResponseEntity<String> response = callWebhook(body, webhookSignature(body));

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(countRows("entitlements", "user_id = ?", buyer.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT razorpay_payment_id FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo(paymentId);

        // FT-HOOK-11: reaching business logic at all proves the raw-body read works. With
        // @RequestBody String instead, Jackson would try to deserialize a JSON object into a
        // String and 400 before signature verification ever ran.
        JSONObject purchased = jsonArrayOf(get("/api/v1/purchases/mine", buyer)).getJSONObject(0);
        assertThat(purchased.getString("status")).isEqualTo("PAID");
    }

    @Test
    @DisplayName("FT-HOOK-06: webhook first, then client confirm — still one entitlement")
    void webhookThenClientConfirmIsIdempotent() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);
        String paymentId = Payloads.razorpayPaymentId();
        String body = Payloads.paymentCaptured(orderId, paymentId).toString();

        assertThat(statusOf(callWebhook(body, webhookSignature(body)))).isEqualTo(200);

        ResponseEntity<String> clientConfirm = post("/api/v1/purchases/confirm", buyer, new JSONObject()
                .put("razorpayOrderId", orderId)
                .put("razorpayPaymentId", paymentId)
                .put("razorpaySignature", paymentSignature(orderId, paymentId)));

        assertThat(statusOf(clientConfirm)).isEqualTo(200);
        assertThat(jsonOf(clientConfirm).getString("status")).isEqualTo("PAID");
        assertThat(countRows("entitlements")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-HOOK-07: client confirm first, then webhook — still one entitlement")
    void clientConfirmThenWebhookIsIdempotent() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);
        String paymentId = Payloads.razorpayPaymentId();

        post("/api/v1/purchases/confirm", buyer, new JSONObject()
                .put("razorpayOrderId", orderId)
                .put("razorpayPaymentId", paymentId)
                .put("razorpaySignature", paymentSignature(orderId, paymentId)));
        assertThat(countRows("entitlements")).isEqualTo(1);

        String body = Payloads.paymentCaptured(orderId, paymentId).toString();
        ResponseEntity<String> webhook = callWebhook(body, webhookSignature(body));

        // Whichever path lands second must be a no-op. Razorpay retries webhooks, so this can
        // happen many times for one payment.
        assertThat(statusOf(webhook)).isEqualTo(200);
        assertThat(countRows("entitlements")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-HOOK-05b: a repeated webhook delivery changes nothing")
    void repeatedWebhookDeliveryIsANoOp() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);
        String body = Payloads.paymentCaptured(orderId, Payloads.razorpayPaymentId()).toString();
        String signature = webhookSignature(body);

        for (int i = 0; i < 3; i++) {
            assertThat(statusOf(callWebhook(body, signature))).isEqualTo(200);
        }

        assertThat(countRows("entitlements")).isEqualTo(1);
        assertThat(countRows("purchases", "status = 'PAID'")).isEqualTo(1);
    }

    // --- Events we don't act on --------------------------------------------------------------------

    @Test
    @DisplayName("FT-HOOK-08: other event types are acknowledged but change nothing")
    void otherEventTypesAreIgnoredSafely() throws Exception {
        Actor buyer = registerUser();
        String orderId = pendingOrderFor(buyer);

        for (String event : java.util.List.of("payment.failed", "payment.authorized", "order.paid", "refund.created")) {
            String body = Payloads.webhookEvent(event, orderId, Payloads.razorpayPaymentId()).toString();

            ResponseEntity<String> response = callWebhook(body, webhookSignature(body));

            // 200 so Razorpay stops retrying, but nothing granted. In particular
            // payment.authorized is NOT payment.captured — authorized money hasn't been taken.
            assertThat(statusOf(response)).as("event %s should be acknowledged", event).isEqualTo(200);
        }
        assertThat(countRows("entitlements")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("FT-HOOK-09: a captured event for an order we don't have is acknowledged, not fatal")
    void unknownOrderIsAcknowledged() throws Exception {
        String body = Payloads.paymentCaptured("order_we_never_created", "pay_x").toString();

        ResponseEntity<String> response = callWebhook(body, webhookSignature(body));

        // Retrying forever on an order that will never exist just fills the log; the service
        // logs a warning and moves on.
        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(countRows("entitlements")).isZero();
    }

    @Test
    @DisplayName("FT-HOOK-10: a correctly signed but malformed payload fails without leaking internals")
    void malformedPayloadFailsCleanly() throws Exception {
        String notJson = "this is not json at all";
        ResponseEntity<String> notJsonResponse = callWebhook(notJson, webhookSignature(notJson));

        String wrongShape = new JSONObject().put("event", "payment.captured").toString();
        ResponseEntity<String> wrongShapeResponse = callWebhook(wrongShape, webhookSignature(wrongShape));

        // Both are past authentication, so they're allowed to fail — but not to hand a stack
        // trace or an internal class name back to a public endpoint.
        for (ResponseEntity<String> response : java.util.List.of(notJsonResponse, wrongShapeResponse)) {
            assertThat(statusOf(response)).isBetween(400, 500);
            if (response.getBody() != null) {
                assertThat(response.getBody())
                        .doesNotContain("org.json")
                        .doesNotContain("com.knowyourinterview")
                        .doesNotContain("Exception");
            }
        }
        assertThat(countRows("entitlements")).isZero();
    }

    @Test
    @DisplayName("FT-HOOK-12: a webhook can't unlock content for a user who has no purchase")
    void webhookCannotInventAPurchase() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor freeloader = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);

        // A correctly signed event referring to an order that was never created for anyone.
        String body = Payloads.paymentCaptured(Payloads.razorpayOrderId(), Payloads.razorpayPaymentId()).toString();
        callWebhook(body, webhookSignature(body));

        // Entitlements are only ever derived from a Purchase row, so there's nothing for a
        // fabricated event to attach to.
        assertThat(countRows("entitlements")).isZero();
        assertThat(teaserOf(freeloader, experienceId).getBoolean("unlocked")).isFalse();
    }
}
