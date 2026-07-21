package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.payment.dto.ConfirmPaymentRequest;
import com.knowyourinterview.api.payment.dto.PurchaseResponse;
import com.knowyourinterview.api.payout.dto.MarkPayoutPaidRequest;
import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.support.ContainerConfig;
import com.razorpay.Utils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other big gap flagged since Phase 3/4: submission -> admin approve -> viewer
 * purchase -> real entitlement -> manual payout, end to end against real Postgres +
 * Redis via Testcontainers, instead of every layer being mocked out individually.
 *
 * Order creation itself (POST /experiences/{id}/purchase) calls the real Razorpay
 * Orders API and isn't exercised here — that's a thin, well-tested third-party SDK
 * call, not our business logic, and this suite is meant to be hermetic (no outbound
 * network calls, no real Razorpay credentials needed). Instead this inserts the
 * Purchase row directly, the way it would exist right after a real order was created,
 * and computes a signature with the same HMAC scheme Razorpay uses
 * (com.razorpay.Utils.getHash — see PurchaseService.confirmPayment) against a
 * known test key secret, so /purchases/confirm's real verification path actually runs.
 *
 * Run via `mvn verify` — needs Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(ContainerConfig.class)
class PurchaseFlowIT {

    private static final String RAZORPAY_KEY_SECRET = "it-test-secret-value";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.razorpay.key-secret", () -> RAZORPAY_KEY_SECRET);
        registry.add("app.storage.proof-dir",
                () -> System.getProperty("java.io.tmpdir") + "/kyi-it-proof-" + UUID.randomUUID());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime() + "@example.com";
    }

    @Test
    void submitApprovePurchaseAndPayOut() throws Exception {
        // --- Contributor drafts, adds a round, uploads proof, and submits ---
        AuthResponse contributor = register("Contributor", unique("contributor"));

        ExperienceFullResponse draft = post(
                "/api/v1/experiences", contributor.accessToken(),
                """
                {"company":"Acme","roleTitle":"Backend Engineer","isRemote":true,
                 "outcome":"OFFER","teaser":"Solid onsite loop, focus on systems design."}
                """,
                ExperienceFullResponse.class).getBody();
        assertThat(draft).isNotNull();
        UUID experienceId = draft.id();

        post(
                "/api/v1/experiences/" + experienceId + "/rounds", contributor.accessToken(),
                """
                {"roundType":"SYSTEM_DESIGN","durationMinutes":45,"difficulty":4}
                """,
                Void.class);

        uploadProof(experienceId, contributor.accessToken());

        ResponseEntity<ExperienceFullResponse> submitted = post(
                "/api/v1/experiences/" + experienceId + "/submit", contributor.accessToken(), null,
                ExperienceFullResponse.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody().status().name()).isEqualTo("PENDING_REVIEW");

        // --- Admin approves: publishes + creates a PENDING payout ledger row ---
        AuthResponse admin = registerAdmin(unique("admin"));

        ResponseEntity<ExperienceFullResponse> approved = post(
                "/api/v1/admin/experiences/" + experienceId + "/approve", admin.accessToken(), null,
                ExperienceFullResponse.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().status().name()).isEqualTo("PUBLISHED");
        int pricePaise = approved.getBody().pricePaise();

        // --- Viewer sees only the teaser before paying ---
        AuthResponse viewer = register("Viewer", unique("viewer"));

        ExperienceViewResponse beforePurchase = get(
                "/api/v1/experiences/" + experienceId, viewer.accessToken(), ExperienceViewResponse.class).getBody();
        assertThat(beforePurchase.entitled()).isFalse();
        assertThat(beforePurchase.teaser()).isNotNull();
        assertThat(beforePurchase.full()).isNull();

        // --- Simulate "Razorpay order already created" and confirm the payment ---
        String razorpayOrderId = "order_it_" + System.nanoTime();
        String razorpayPaymentId = "pay_it_" + System.nanoTime();
        purchaseRepository.save(new Purchase(
                UUID.randomUUID(), viewer.user().id(), experienceId, pricePaise, razorpayOrderId));
        String signature = Utils.getHash(razorpayOrderId + "|" + razorpayPaymentId, RAZORPAY_KEY_SECRET);

        ResponseEntity<PurchaseResponse> confirmed = post(
                "/api/v1/purchases/confirm", viewer.accessToken(),
                new ConfirmPaymentRequest(razorpayOrderId, razorpayPaymentId, signature), PurchaseResponse.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().status().name()).isEqualTo("PAID");

        // --- Now the viewer is really entitled to the full write-up ---
        ExperienceViewResponse afterPurchase = get(
                "/api/v1/experiences/" + experienceId, viewer.accessToken(), ExperienceViewResponse.class).getBody();
        assertThat(afterPurchase.entitled()).isTrue();
        assertThat(afterPurchase.full()).isNotNull();
        assertThat(afterPurchase.full().rounds()).hasSize(1);

        ResponseEntity<List<PurchaseResponse>> myPurchases = getList(
                "/api/v1/purchases/mine", viewer.accessToken(),
                new ParameterizedTypeReference<List<PurchaseResponse>>() {});
        assertThat(myPurchases.getBody()).extracting(PurchaseResponse::status).contains(Purchase.Status.PAID);

        // --- Confirming an already-PAID order again is idempotent, even with a bogus
        // signature — PurchaseService.confirmPayment short-circuits to "already paid"
        // before it ever checks the signature. That's deliberate (lets the webhook
        // backup and the client-confirm call race safely) and doesn't grant anything
        // new — it just returns the existing PAID purchase for the owning user — but
        // it's worth knowing this call doesn't re-verify on repeat.
        ResponseEntity<PurchaseResponse> repeatConfirm = post(
                "/api/v1/purchases/confirm", viewer.accessToken(),
                new ConfirmPaymentRequest(razorpayOrderId, razorpayPaymentId, "not-a-real-signature"),
                PurchaseResponse.class);
        assertThat(repeatConfirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeatConfirm.getBody().status().name()).isEqualTo("PAID");

        // --- Admin sees the payout owed, marks it paid manually ---
        ResponseEntity<List<PayoutResponse>> queue = getList(
                "/api/v1/admin/payouts", admin.accessToken(), new ParameterizedTypeReference<List<PayoutResponse>>() {});
        PayoutResponse owed = queue.getBody().stream()
                .filter(p -> p.experienceId().equals(experienceId))
                .findFirst()
                .orElseThrow();
        assertThat(owed.status()).isEqualTo(com.knowyourinterview.api.experience.Payout.Status.PENDING);
        assertThat(owed.contributorEmail()).isEqualTo(contributor.user().email());

        ResponseEntity<PayoutResponse> markedPaid = post(
                "/api/v1/admin/payouts/" + owed.id() + "/mark-paid", admin.accessToken(),
                new MarkPayoutPaidRequest("UPI-IT-TEST-REF"), PayoutResponse.class);
        assertThat(markedPaid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(markedPaid.getBody().status()).isEqualTo(com.knowyourinterview.api.experience.Payout.Status.PAID);

        // --- Contributor can see it's paid ---
        ResponseEntity<List<PayoutResponse>> myPayouts = getList(
                "/api/v1/payouts/mine", contributor.accessToken(),
                new ParameterizedTypeReference<List<PayoutResponse>>() {});
        assertThat(myPayouts.getBody())
                .filteredOn(p -> p.experienceId().equals(experienceId))
                .extracting(PayoutResponse::status)
                .containsExactly(com.knowyourinterview.api.experience.Payout.Status.PAID);
    }

    private AuthResponse register(String displayName, String email) {
        return restTemplate.postForObject(
                "/api/v1/auth/register", new RegisterBody(email, "correct-horse-battery-staple", displayName),
                AuthResponse.class);
    }

    /** Registers, then flips is_admin the same way local dev bootstraps its first admin
     * (see docs/04-handoff.md) and re-logs-in — the JWT bakes the admin claim in at
     * issuance time, so a token minted before the flip wouldn't carry it. */
    private AuthResponse registerAdmin(String email) {
        register("Admin", email);
        jdbcTemplate.update("UPDATE users SET is_admin = true WHERE email = ?", email);
        return restTemplate.postForObject(
                "/api/v1/auth/login", new LoginBody(email, "correct-horse-battery-staple"), AuthResponse.class);
    }

    private void uploadProof(UUID experienceId, String accessToken) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource("not a real PDF, just IT test bytes".getBytes()) {
            @Override
            public String getFilename() {
                return "proof.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/experiences/" + experienceId + "/proof", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private <T> ResponseEntity<T> post(String path, String accessToken, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) headers.setBearerAuth(accessToken);
        // Explicit, because some callers above pass a raw JSON String as the body (for
        // brevity) rather than a record — without this, RestTemplate's
        // StringHttpMessageConverter would label it text/plain and Spring MVC's
        // @RequestBody would 415 rather than parse it.
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
    }

    private <T> ResponseEntity<T> get(String path, String accessToken, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), responseType);
    }

    private <T> ResponseEntity<T> getList(String path, String accessToken, ParameterizedTypeReference<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), type);
    }

    private record RegisterBody(String email, String password, String displayName) {}

    private record LoginBody(String email, String password) {}
}
