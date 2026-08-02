package com.knowyourinterview.api.functional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-PAY — order guards, payment confirmation, and the entitlement that unlocks paid content.
 * See {@code docs/09-test-plan.md} §7.10.
 *
 * <p>Every case here is about one of two failures: someone reading paid content without paying,
 * or someone being charged/credited twice. Signature verification is exercised for real — the
 * tests compute the same HMAC Razorpay would, so nothing is stubbed past the point where the
 * money decision is made. Order <em>creation</em> is the one step not exercised (it would need an
 * outbound call); see {@code docs/09-test-plan.md} §6.1 and gap G2.
 */
class PurchaseFunctionalIT extends FunctionalTestBase {

    private JSONObject confirmBody(String orderId, String paymentId, String signature) {
        return new JSONObject()
                .put("razorpayOrderId", orderId)
                .put("razorpayPaymentId", paymentId)
                .put("razorpaySignature", signature);
    }

    // --- Order creation guards -----------------------------------------------------------------

    @Test
    @DisplayName("FT-PAY-01: you can't start checkout on an unpublished experience")
    void checkoutIsRefusedForUnpublishedExperience() {
        Actor contributor = registerUser();
        Actor buyer = registerUser();
        UUID draft = submittedExperience(contributor);

        ResponseEntity<String> response = post("/api/v1/experiences/" + draft + "/purchase", buyer, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("isn't published yet");
        assertThat(countRows("purchases")).isZero();
    }

    @Test
    @DisplayName("FT-PAY-02: you can't be charged for a free experience")
    void checkoutIsRefusedForFreeExperience() {
        Actor contributor = registerUser();
        Actor buyer = registerUser();
        UUID free = createDraft(contributor, Payloads.freeContribution());
        addRound(contributor, free);
        post("/api/v1/experiences/" + free + "/submit", contributor, null);

        ResponseEntity<String> response = post("/api/v1/experiences/" + free + "/purchase", buyer, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("free");
        assertThat(countRows("purchases")).isZero();
    }

    @Test
    @DisplayName("FT-PAY-03: you can't be charged twice for the same experience")
    void checkoutIsRefusedWhenAlreadyEntitled() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        purchase(buyer, experienceId);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/purchase", buyer, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("already have access");
        assertThat(countRows("purchases")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-PAY-04: checkout on an unknown experience is a 404")
    void checkoutOnUnknownExperienceIs404() {
        Actor buyer = registerUser();

        assertThat(statusOf(post("/api/v1/experiences/" + UUID.randomUUID() + "/purchase", buyer, null)))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("FT-PAY-05: the guards run before the payment-provider configuration check")
    void guardsRunBeforeTheProviderConfigurationCheck() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/purchase", buyer, null);

        // This suite deliberately leaves app.razorpay.key-id blank so no outbound call is ever
        // made. Reaching THIS message rather than one of the guard messages above is what proves
        // the guards fire first — which matters, because an ordering slip would let a request for
        // an unpublished or already-owned experience reach the provider and create a real order.
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("payments aren't configured correctly");
        assertThat(countRows("purchases")).isZero();
    }

    // --- Confirmation --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-PAY-06/12: a valid signature grants exactly one entitlement and unlocks the content")
    void validSignatureGrantsEntitlementAndUnlocksContent() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin, Payloads.experience()
                .put("prepAdvice", "PAID-PREP-ADVICE")
                .put("confidentialNote", "PRIVATE-CONTRIBUTOR-NOTE"));

        // Before paying: teaser only.
        assertThat(teaserOf(buyer, experienceId).getBoolean("unlocked")).isFalse();

        String orderId = createdPurchase(buyer, experienceId);
        String paymentId = Payloads.razorpayPaymentId();
        ResponseEntity<String> response = post("/api/v1/purchases/confirm", buyer,
                confirmBody(orderId, paymentId, paymentSignature(orderId, paymentId)));

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject purchase = jsonOf(response);
        assertThat(purchase.getString("status")).isEqualTo("PAID");
        assertThat(purchase.getLong("amountPaise")).isEqualTo(DEFAULT_PRICE_PAISE);
        assertThat(purchase.getString("company")).isEqualTo("Acme Corp");
        assertThat(countRows("entitlements", "user_id = ? AND experience_id = ?", buyer.id(), experienceId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT razorpay_payment_id FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo(paymentId);

        // FT-PAY-12: the content is now readable — but the confidential note still isn't.
        ResponseEntity<String> afterPayment = get("/api/v1/experiences/" + experienceId, buyer);
        JSONObject full = jsonOf(afterPayment).getJSONObject("full");
        assertThat(jsonOf(afterPayment).getBoolean("entitled")).isTrue();
        assertThat(full.getJSONArray("rounds").length()).isEqualTo(1);
        assertThat(full.getString("prepAdvice")).isEqualTo("PAID-PREP-ADVICE");
        assertThat(full.isNull("confidentialNote")).isTrue();
        assertThat(afterPayment.getBody()).doesNotContain("PRIVATE-CONTRIBUTOR-NOTE");
    }

    @Test
    @DisplayName("FT-PAY-07: a bad signature grants nothing and marks the purchase failed")
    void badSignatureGrantsNothing() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor attacker = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        String orderId = createdPurchase(attacker, experienceId);

        ResponseEntity<String> response = post("/api/v1/purchases/confirm", attacker,
                confirmBody(orderId, Payloads.razorpayPaymentId(), "0000000000000000000000000000000000000000"));

        // Without this check, anyone could POST a confirmation for their own unpaid order and
        // unlock the content for free. The signature is the only thing proving money moved.
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("could not be verified");
        assertThat(countRows("entitlements")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo("FAILED");
        assertThat(teaserOf(attacker, experienceId).getBoolean("unlocked")).isFalse();
    }

    @Test
    @DisplayName("FT-PAY-08: a signature is bound to its own order and payment ids")
    void signatureIsBoundToItsOwnOrder() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID first = publishedExperience(contributor, admin, Payloads.experience("FirstCo", "Engineer", "1."));
        UUID second = publishedExperience(contributor, admin, Payloads.experience("SecondCo", "Engineer", "2."));

        String paidOrder = createdPurchase(buyer, first);
        String paidPayment = Payloads.razorpayPaymentId();
        post("/api/v1/purchases/confirm", buyer, confirmBody(paidOrder, paidPayment,
                paymentSignature(paidOrder, paidPayment)));

        // Replay the signature from the genuinely paid order against a different, unpaid one.
        String unpaidOrder = createdPurchase(buyer, second);
        ResponseEntity<String> replay = post("/api/v1/purchases/confirm", buyer,
                confirmBody(unpaidOrder, paidPayment, paymentSignature(paidOrder, paidPayment)));

        assertThat(statusOf(replay)).isEqualTo(400);
        assertThat(countRows("entitlements", "experience_id = ?", second)).isZero();
        assertThat(countRows("entitlements")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-PAY-09: confirming someone else's order is a 404, not a hint that it exists")
    void confirmingSomeoneElsesOrderIsRefused() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        Actor attacker = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        String orderId = createdPurchase(buyer, experienceId);
        String paymentId = Payloads.razorpayPaymentId();

        // Note the signature is genuinely valid — the only thing wrong is who's calling.
        ResponseEntity<String> response = post("/api/v1/purchases/confirm", attacker,
                confirmBody(orderId, paymentId, paymentSignature(orderId, paymentId)));

        assertThat(statusOf(response)).isEqualTo(404);
        assertThat(countRows("entitlements")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId))
                .as("a stranger's failed attempt must not mark the buyer's own order FAILED")
                .isEqualTo("CREATED");
    }

    @Test
    @DisplayName("FT-PAY-10: confirming an unknown order is a 404")
    void confirmingUnknownOrderIs404() {
        Actor buyer = registerUser();

        ResponseEntity<String> response = post("/api/v1/purchases/confirm", buyer,
                confirmBody("order_does_not_exist", "pay_x", "signature"));

        assertThat(statusOf(response)).isEqualTo(404);
    }

    @Test
    @DisplayName("FT-PAY-11: confirming an already-paid order is idempotent")
    void confirmingAnAlreadyPaidOrderIsIdempotent() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        String orderId = createdPurchase(buyer, experienceId);
        String paymentId = Payloads.razorpayPaymentId();
        post("/api/v1/purchases/confirm", buyer, confirmBody(orderId, paymentId,
                paymentSignature(orderId, paymentId)));

        // Deliberately a junk signature: confirmPayment short-circuits on an already-PAID order
        // before it re-verifies. That's what lets the client-confirm and webhook paths race
        // safely, and it grants nothing new — but it does mean a repeat call isn't re-checked,
        // which is worth pinning so nobody assumes otherwise.
        ResponseEntity<String> repeat = post("/api/v1/purchases/confirm", buyer,
                confirmBody(orderId, paymentId, "not-a-real-signature"));

        assertThat(statusOf(repeat)).isEqualTo(200);
        assertThat(jsonOf(repeat).getString("status")).isEqualTo("PAID");
        assertThat(countRows("entitlements")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-PAY-18: confirmation validates that all three fields are present")
    void confirmationValidatesItsFields() {
        Actor buyer = registerUser();

        ResponseEntity<String> response = post("/api/v1/purchases/confirm", buyer,
                confirmBody("", "", ""));

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(jsonOf(response).getJSONObject("fieldErrors").keySet())
                .contains("razorpayOrderId", "razorpayPaymentId", "razorpaySignature");
    }

    // --- Entitlement scope ----------------------------------------------------------------------

    @Test
    @DisplayName("FT-PAY-13/14: an entitlement is scoped to one buyer and one experience")
    void entitlementIsScopedToBuyerAndExperience() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        Actor otherViewer = registerUser();
        UUID bought = publishedExperience(contributor, admin, Payloads.experience("BoughtCo", "Engineer", "B."));
        UUID notBought = publishedExperience(contributor, admin, Payloads.experience("OtherCo", "Engineer", "N."));
        purchase(buyer, bought);

        // FT-PAY-13: paying doesn't unlock it for anyone else.
        assertThat(teaserOf(otherViewer, bought).getBoolean("unlocked")).isFalse();
        // FT-PAY-14: nor does it unlock a different experience for the same buyer.
        assertThat(teaserOf(buyer, notBought).getBoolean("unlocked")).isFalse();
        assertThat(fullOf(buyer, bought).getJSONArray("rounds").length()).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-PAY-15: the database refuses a duplicate entitlement outright")
    void databaseRefusesDuplicateEntitlements() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        purchase(buyer, experienceId);
        String purchaseId = jdbc.queryForObject(
                "SELECT id::text FROM purchases WHERE user_id = ?", String.class, buyer.id());

        // The application checks before inserting, but application checks have races. UNIQUE
        // (user_id, experience_id) is the guarantee that survives one — worth proving it's
        // actually there rather than trusting the migration was applied.
        assertThatDuplicateEntitlementIsRejected(buyer.id(), experienceId, UUID.fromString(purchaseId));
        assertThat(countRows("entitlements")).isEqualTo(1);
    }

    private void assertThatDuplicateEntitlementIsRejected(UUID userId, UUID experienceId, UUID purchaseId) {
        try {
            jdbc.update("INSERT INTO entitlements (id, user_id, experience_id, purchase_id) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), userId, experienceId, purchaseId);
            org.assertj.core.api.Assertions.fail(
                    "expected UNIQUE (user_id, experience_id) on entitlements to reject a duplicate");
        } catch (org.springframework.dao.DataAccessException expected) {
            // This is the pass condition.
            assertThat(expected).isNotNull();
        }
    }

    @Test
    @DisplayName("FT-PAY-16: unlockCount reflects how many people actually bought it")
    void unlockCountReflectsRealPurchases() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);
        assertThat(fullOf(contributor, experienceId).getLong("unlockCount")).isZero();

        purchase(registerUser(), experienceId);
        purchase(registerUser(), experienceId);

        assertThat(fullOf(contributor, experienceId).getLong("unlockCount")).isEqualTo(2);
        assertThat(fullOf(admin, experienceId).getLong("unlockCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("FT-PAY-17: /purchases/mine only ever returns the caller's own purchases")
    void purchasesMineIsScopedToTheCaller() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor firstBuyer = registerUser();
        Actor secondBuyer = registerUser();
        UUID first = publishedExperience(contributor, admin, Payloads.experience("FirstCo", "Engineer", "1."));
        UUID second = publishedExperience(contributor, admin, Payloads.experience("SecondCo", "Engineer", "2."));
        purchase(firstBuyer, first);
        purchase(secondBuyer, second);

        JSONArray firstsPurchases = jsonArrayOf(get("/api/v1/purchases/mine", firstBuyer));
        JSONArray secondsPurchases = jsonArrayOf(get("/api/v1/purchases/mine", secondBuyer));

        assertThat(firstsPurchases.length()).isEqualTo(1);
        assertThat(firstsPurchases.getJSONObject(0).getString("experienceId")).isEqualTo(first.toString());
        // My Library shows what was bought, so the experience details have to ride along.
        assertThat(firstsPurchases.getJSONObject(0).getString("company")).isEqualTo("FirstCo");
        assertThat(firstsPurchases.getJSONObject(0).getString("status")).isEqualTo("PAID");
        assertThat(secondsPurchases.length()).isEqualTo(1);
        assertThat(secondsPurchases.getJSONObject(0).getString("experienceId")).isEqualTo(second.toString());
    }

    // --- Concurrency ----------------------------------------------------------------------------

    @Test
    @DisplayName("FT-PAY-19: two simultaneous confirmations of one order grant exactly one entitlement")
    void concurrentConfirmationsGrantExactlyOneEntitlement() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        String orderId = createdPurchase(buyer, experienceId);
        String paymentId = Payloads.razorpayPaymentId();
        JSONObject body = confirmBody(orderId, paymentId, paymentSignature(orderId, paymentId));

        // The real-world shape of this: the browser's success handler fires at the same moment
        // Razorpay's webhook lands. Both paths funnel into grantEntitlement, and the exists-check
        // inside it has a race window that only the UNIQUE constraint closes.
        int attempts = 4;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>(attempts);
            for (int i = 0; i < attempts; i++) {
                Callable<Integer> call = () -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return statusOf(post("/api/v1/purchases/confirm", buyer, body));
                };
                futures.add(pool.submit(call));
            }
            startTogether.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(countRows("entitlements")).as("a viewer must be unlocked at most once").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM purchases WHERE razorpay_order_id = ?",
                String.class, orderId)).isEqualTo("PAID");
        // A losing racer may legitimately see a 409 (optimistic lock on Purchase) — what must
        // not happen is a 500, or a success that granted a second entitlement.
        assertThat(statuses).as("no confirmation may fail with a server error").doesNotContain(500);
        assertThat(statuses).contains(200);
    }
}
