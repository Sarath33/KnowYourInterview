package com.knowyourinterview.api.functional.support;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Valid-by-default request bodies, so each test only has to state the one thing it's varying.
 *
 * <p>Bodies are built with {@code org.json} rather than Jackson on purpose. org.json is already
 * on the runtime classpath (it comes with the Razorpay SDK, and {@code WebhookController} uses
 * it directly), it's version-stable, and — unlike a {@code Map} round-tripped through a message
 * converter — it lets a test send things a typed DTO can't express: an explicit JSON {@code null}
 * ({@link JSONObject#NULL}), a wrong-typed field, an unknown enum value, or outright malformed
 * JSON. Several negative cases in the plan depend on exactly that.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * A minimal-but-valid {@code ExperienceRequest}. Every required field is populated and every
     * bounded field sits inside its range, so a test that wants a 400 has to break something
     * deliberately.
     */
    public static JSONObject experience() {
        return new JSONObject()
                .put("company", "Acme Corp")
                .put("roleTitle", "Backend Engineer")
                .put("level", "L4")
                .put("location", "Bengaluru")
                .put("isRemote", true)
                .put("interviewMonth", 6)
                .put("interviewYear", 2026)
                .put("outcome", "OFFER")
                .put("teaser", "Four-round loop, heavy on systems design and debugging.")
                .put("prepAdvice", "Revise consistent hashing and read the DDIA chapters on replication.")
                .put("overallDifficulty", 4)
                .put("timeline", "Three weeks from recruiter screen to offer.")
                .put("compensation", "Above band midpoint.");
    }

    /** An experience with a distinguishing company/role/teaser, for browse and filter cases. */
    public static JSONObject experience(String company, String roleTitle, String teaser) {
        return experience().put("company", company).put("roleTitle", roleTitle).put("teaser", teaser);
    }

    /** A contributor's own free submission — publishes on submit without admin review. */
    public static JSONObject freeContribution() {
        return experience().put("freeContribution", true);
    }

    /** An admin-only "reference a public source" submission — free, but still reviewed. */
    public static JSONObject referenceSubmission() {
        return experience()
                .put("sourceUrl", "https://example.com/some-public-writeup")
                .put("sourceName", "Example Blog");
    }

    /** A minimal-but-valid {@code RoundRequest}. */
    public static JSONObject round() {
        return new JSONObject()
                .put("roundType", "SYSTEM_DESIGN")
                .put("durationMinutes", 45)
                .put("questionsAsked", "Design a rate limiter that survives a node loss.")
                .put("topicsTags", new JSONArray().put("system-design").put("distributed-systems"))
                .put("approach", "Started from the token bucket, then moved the counter into Redis.")
                .put("interviewerBehavior", "Collaborative; nudged rather than led.")
                .put("difficulty", 4);
    }

    public static JSONObject round(String roundType) {
        return round().put("roundType", roundType);
    }

    /**
     * A Razorpay {@code payment.captured} webhook event, shaped exactly as
     * {@code WebhookController} unwraps it: {@code payload.payment.entity.{id,order_id}}.
     */
    public static JSONObject webhookEvent(String event, String razorpayOrderId, String razorpayPaymentId) {
        return new JSONObject()
                .put("entity", "event")
                .put("event", event)
                .put("contains", new JSONArray().put("payment"))
                .put("payload", new JSONObject()
                        .put("payment", new JSONObject()
                                .put("entity", new JSONObject()
                                        .put("id", razorpayPaymentId)
                                        .put("entity", "payment")
                                        .put("order_id", razorpayOrderId)
                                        .put("status", "captured")
                                        .put("amount", 9900)
                                        .put("currency", "INR"))));
    }

    public static JSONObject paymentCaptured(String razorpayOrderId, String razorpayPaymentId) {
        return webhookEvent("payment.captured", razorpayOrderId, razorpayPaymentId);
    }

    /** A plausible-looking Razorpay order id, unique per call. */
    public static String razorpayOrderId() {
        return "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    /** A plausible-looking Razorpay payment id, unique per call. */
    public static String razorpayPaymentId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
