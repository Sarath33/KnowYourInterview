package com.knowyourinterview.api.functional.support;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.knowyourinterview.api.payment.Purchase;
import com.knowyourinterview.api.payment.PurchaseRepository;
import com.razorpay.Utils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared harness for the functional (black-box HTTP, real infrastructure) suite. See
 * {@code docs/09-test-plan.md} for the test design this implements.
 *
 * <h2>What a functional test here actually exercises</h2>
 * A real embedded server on a random port, the real Spring Security filter chain (rate limiter,
 * JWT filter, authorization rules, CORS), real Spring MVC binding and validation, real
 * transactions, real Flyway-migrated PostgreSQL, and real Redis. Nothing is mocked except
 * {@code GoogleIdTokenVerifierPort} (see {@link FunctionalTestConfig}). Razorpay is never
 * called — see the {@code app.razorpay.key-id} note below.
 *
 * <h2>One Spring context for the whole suite</h2>
 * Every subclass inherits this class's annotations and adds none of its own. That matters: the
 * Spring TestContext framework caches contexts by their configuration, so as long as no subclass
 * introduces its own {@code @TestPropertySource}, {@code @MockitoBean} or extra
 * {@code @DynamicPropertySource}, all of them share a single context and a single application
 * startup. Adding one to a subclass silently doubles the suite's runtime, so put shared
 * configuration here instead.
 *
 * <h2>Isolation</h2>
 * Because the context (and therefore the database) is shared, {@link #resetState()} truncates
 * every application table and flushes Redis before each test. Tests may assume an empty
 * database and must not assume anything about execution order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(FunctionalTestConfig.class)
public abstract class FunctionalTestBase {

    // --- Fixed configuration the tests assert against -------------------------------------

    protected static final String PASSWORD = "correct-horse-battery-staple";
    protected static final String RAZORPAY_KEY_SECRET = "ft-razorpay-key-secret";
    protected static final String RAZORPAY_WEBHOOK_SECRET = "ft-razorpay-webhook-secret";
    protected static final String ADMIN_BOOTSTRAP_SECRET = "ft-admin-bootstrap-secret-value";

    /** {@code app.pricing.default-price-paise} — the viewer unlock price stamped at creation. */
    protected static final long DEFAULT_PRICE_PAISE = 9900L;
    /** {@code app.pricing.contributor-payout-paise} — the flat fee owed on approval. */
    protected static final long CONTRIBUTOR_PAYOUT_PAISE = 50000L;

    /** Every table Flyway creates, in an order that doesn't matter because of CASCADE. */
    private static final String TRUNCATE_ALL = """
            TRUNCATE TABLE
                experience_views, entitlements, purchases, payouts, payout_accounts,
                review_logs, experience_edit_snapshots, proof_documents, experience_rounds,
                experiences, password_reset_tokens, email_verification_tokens, users
            RESTART IDENTITY CASCADE
            """;

    private static final Path PROOF_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "kyi-ft-proof-" + UUID.randomUUID());

    private static final AtomicInteger CLIENT_IP_SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger EMAIL_SEQUENCE = new AtomicInteger(1);

    @DynamicPropertySource
    static void functionalTestProperties(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry);

        // Deliberately blank: PurchaseService#createOrder checks its guards (not published,
        // free, already entitled, unknown experience) BEFORE it checks configuration, so those
        // paths are fully covered while the actual Razorpay Orders API call is never reached.
        // Keeping the suite free of outbound network calls is a hard requirement — see
        // docs/09-test-plan.md §6.1/§6.3.
        registry.add("app.razorpay.key-id", () -> "");
        // Non-blank, because signature verification on /purchases/confirm and on the webhook
        // is real business logic and IS exercised — the tests compute matching signatures with
        // the same HMAC helper the SDK verifies with.
        registry.add("app.razorpay.key-secret", () -> RAZORPAY_KEY_SECRET);
        registry.add("app.razorpay.webhook-secret", () -> RAZORPAY_WEBHOOK_SECRET);

        // Enables POST /auth/bootstrap-admin, which is how these tests mint admins — using the
        // real endpoint rather than a direct UPDATE keeps the promotion path itself covered.
        registry.add("app.admin-bootstrap.secret", () -> ADMIN_BOOTSTRAP_SECRET);

        // Uploads go to a per-JVM temp directory, never api/uploads/.
        registry.add("app.storage.proof-dir", PROOF_DIR::toString);

        // Pinned blank so EmailConfig always selects LoggingEmailSender. Two reasons: no test
        // may ever open an SMTP connection (a stray MAIL_HOST in the environment would
        // otherwise make the suite try to mail real addresses), and EmailConfirmationFunctionalIT
        // reads confirmation links out of what that sender logs.
        registry.add("spring.mail.host", () -> "");

        // Two reasons, both deliberate. (1) It lets every request carry its own synthetic
        // client IP, so fixture setup can't exhaust the 5-registrations-per-minute bucket and
        // rate limiting never becomes a source of flakiness. (2) It's the configuration the
        // deployed app actually runs with (Railway sits in front), so this is the mode worth
        // testing. Cases that target the limiter pin a fixed IP on purpose.
        registry.add("app.rate-limit.trust-forwarded-for", () -> "true");
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected PurchaseRepository purchaseRepository;

    /**
     * Truncating rather than rolling back: these are black-box HTTP tests, so the writes happen
     * on the server's own threads and transactions and there is no test-managed transaction to
     * roll back. {@code flyway_schema_history} is untouched, so the schema survives.
     */
    @BeforeEach
    protected void resetState() {
        jdbc.execute(TRUNCATE_ALL);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    // --- Actors ---------------------------------------------------------------------------

    /** A registered user plus the tokens and identity a test needs to act as them. */
    protected record Actor(UUID id, String email, String displayName, String accessToken, String refreshToken) {
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + EMAIL_SEQUENCE.getAndIncrement() + "-" + System.nanoTime() + "@example.test";
    }

    /**
     * Registers a fresh ordinary user through the real registration endpoint, then confirms
     * their email address so they can actually do things.
     * <p>
     * The confirmation is applied with a direct UPDATE rather than by following the emailed
     * link, because the raw token only exists inside the message — the database keeps just its
     * hash — so there is nothing for a fixture to redeem. That's a deliberate trade: this
     * fixture exists to get tests to the behaviour they're actually about, and the
     * confirmation flow itself is covered properly by {@code EmailConfirmationFunctionalIT}
     * (which intercepts the real message) and by the gate tests that use
     * {@link #registerUnconfirmedUser()}.
     * <p>
     * No re-login needed afterwards, unlike {@link #registerAdmin()}: the gate reads the
     * database on every call rather than trusting a JWT claim, precisely so confirming takes
     * effect without waiting for a new token.
     */
    protected Actor registerUser() {
        return registerUser("Test User");
    }

    protected Actor registerUser(String displayName) {
        return registerUser(displayName, uniqueEmail("user"));
    }

    protected Actor registerUser(String displayName, String email) {
        Actor actor = registerUnconfirmedUser(displayName, email);
        confirmEmail(email);
        return actor;
    }

    /** Registers without confirming — for tests about the confirm-your-email gate itself. */
    protected Actor registerUnconfirmedUser() {
        return registerUnconfirmedUser("Unconfirmed User", uniqueEmail("unconfirmed"));
    }

    protected Actor registerUnconfirmedUser(String displayName, String email) {
        ResponseEntity<String> response = post("/api/v1/auth/register", null, registerBody(email, PASSWORD, displayName));
        assertThat(response.getStatusCode().value())
                .as("registration fixture failed: %s", response.getBody())
                .isEqualTo(201);
        return actorFrom(response, email, displayName);
    }

    /** Marks an address confirmed directly. See registerUser for why this isn't done by
     * following the link. */
    protected void confirmEmail(String email) {
        jdbc.update("UPDATE users SET email_verified = true WHERE email = ?", email);
    }

    /**
     * Registers a user and promotes them via the real {@code /auth/bootstrap-admin} endpoint,
     * then logs in again — the {@code admin} claim is baked into the JWT at issue time, so a
     * token minted before the promotion would not carry it.
     */
    protected Actor registerAdmin() {
        String email = uniqueEmail("admin");
        registerUser("Test Admin", email);

        ResponseEntity<String> promotion = post("/api/v1/auth/bootstrap-admin", null,
                new JSONObject().put("email", email).put("secret", ADMIN_BOOTSTRAP_SECRET));
        assertThat(promotion.getStatusCode().value())
                .as("admin bootstrap fixture failed: %s", promotion.getBody())
                .isEqualTo(200);

        return login(email, PASSWORD);
    }

    protected Actor login(String email, String password) {
        ResponseEntity<String> response = post("/api/v1/auth/login", null, loginBody(email, password));
        assertThat(response.getStatusCode().value())
                .as("login fixture failed: %s", response.getBody())
                .isEqualTo(200);
        return actorFrom(response, email, null);
    }

    private Actor actorFrom(ResponseEntity<String> response, String email, String displayName) {
        JSONObject body = new JSONObject(response.getBody());
        JSONObject user = body.getJSONObject("user");
        return new Actor(
                UUID.fromString(user.getString("id")),
                user.getString("email"),
                displayName != null ? displayName : user.optString("displayName", null),
                body.getString("accessToken"),
                body.getString("refreshToken"));
    }

    protected static JSONObject registerBody(String email, String password, String displayName) {
        return new JSONObject().put("email", email).put("password", password).put("displayName", displayName);
    }

    protected static JSONObject loginBody(String email, String password) {
        return new JSONObject().put("email", email).put("password", password);
    }

    // --- HTTP -----------------------------------------------------------------------------

    /**
     * A distinct synthetic client IP per request. With
     * {@code app.rate-limit.trust-forwarded-for=true} this is what the rate limiter buckets on,
     * so ordinary test traffic never collides with a limit. Tests that want to hit a limit call
     * the {@code fromIp} variants with a fixed address.
     */
    protected static String nextClientIp() {
        int n = CLIENT_IP_SEQUENCE.getAndIncrement();
        return "10." + ((n >> 16) & 0xFF) + "." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }

    protected HttpHeaders headers(String accessToken) {
        return headersFromIp(accessToken, nextClientIp());
    }

    protected HttpHeaders headersFromIp(String accessToken, String clientIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientIp);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return headers;
    }

    protected ResponseEntity<String> get(String path, Actor actor) {
        return exchange(HttpMethod.GET, path, headers(actor == null ? null : actor.accessToken()), null);
    }

    protected ResponseEntity<String> getAnonymously(String path) {
        return exchange(HttpMethod.GET, path, headers(null), null);
    }

    protected ResponseEntity<String> post(String path, Actor actor, Object body) {
        return exchange(HttpMethod.POST, path, headers(actor == null ? null : actor.accessToken()), body);
    }

    protected ResponseEntity<String> put(String path, Actor actor, Object body) {
        return exchange(HttpMethod.PUT, path, headers(actor == null ? null : actor.accessToken()), body);
    }

    protected ResponseEntity<String> delete(String path, Actor actor) {
        return exchange(HttpMethod.DELETE, path, headers(actor == null ? null : actor.accessToken()), null);
    }

    /**
     * The single exit point for every request the suite makes.
     *
     * <p>Bodies are serialized to a JSON string here rather than handed to a message converter,
     * so what the test wrote is byte-for-byte what the server receives. That matters for the
     * webhook cases, where the HMAC is computed over the exact request body.
     */
    protected ResponseEntity<String> exchange(HttpMethod method, String path, HttpHeaders headers, Object body) {
        String payload = null;
        if (body != null) {
            payload = body.toString();
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    /** Multipart upload, used for proof documents. */
    protected ResponseEntity<String> postFile(
            String path, Actor actor, String fileName, String contentType, byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        if (contentType != null) {
            partHeaders.setContentType(MediaType.parseMediaType(contentType));
        }
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        parts.add("file", new HttpEntity<>(resource, partHeaders));

        HttpHeaders headers = headers(actor == null ? null : actor.accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
    }

    // --- Response helpers -------------------------------------------------------------------

    protected static int statusOf(ResponseEntity<String> response) {
        return response.getStatusCode().value();
    }

    protected static JSONObject jsonOf(ResponseEntity<String> response) {
        assertThat(response.getBody()).as("expected a JSON body, got none").isNotNull();
        return new JSONObject(response.getBody());
    }

    protected static JSONArray jsonArrayOf(ResponseEntity<String> response) {
        assertThat(response.getBody()).as("expected a JSON array body, got none").isNotNull();
        return new JSONArray(response.getBody());
    }

    /** The {@code message} field of an {@code ApiExceptionHandler} error body. */
    protected static String messageOf(ResponseEntity<String> response) {
        return jsonOf(response).optString("message", "");
    }

    /** Reads a possibly-JSON-null string field as a Java null rather than the string "null". */
    protected static String nullableString(JSONObject object, String field) {
        return object.isNull(field) ? null : object.getString(field);
    }

    // --- Domain fixtures ---------------------------------------------------------------------

    /** Creates a draft with the default valid payload and returns its id. */
    protected UUID createDraft(Actor contributor) {
        return createDraft(contributor, Payloads.experience());
    }

    protected UUID createDraft(Actor contributor, JSONObject body) {
        ResponseEntity<String> response = post("/api/v1/experiences", contributor, body);
        assertThat(statusOf(response))
                .as("draft fixture failed: %s", response.getBody())
                .isEqualTo(201);
        return UUID.fromString(jsonOf(response).getString("id"));
    }

    protected UUID addRound(Actor contributor, UUID experienceId) {
        return addRound(contributor, experienceId, Payloads.round());
    }

    protected UUID addRound(Actor contributor, UUID experienceId, JSONObject body) {
        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/rounds", contributor, body);
        assertThat(statusOf(response))
                .as("round fixture failed: %s", response.getBody())
                .isEqualTo(201);
        return UUID.fromString(jsonOf(response).getString("id"));
    }

    protected UUID uploadProof(Actor contributor, UUID experienceId) {
        return uploadProof(contributor, experienceId, "offer-letter.pdf", "application/pdf",
                "%PDF-1.4 functional test proof bytes".getBytes());
    }

    protected UUID uploadProof(
            Actor contributor, UUID experienceId, String fileName, String contentType, byte[] content) {
        ResponseEntity<String> response = postFile(
                "/api/v1/experiences/" + experienceId + "/proof", contributor, fileName, contentType, content);
        assertThat(statusOf(response))
                .as("proof fixture failed: %s", response.getBody())
                .isEqualTo(201);
        return UUID.fromString(jsonOf(response).getString("id"));
    }

    /** Draft + one round + one proof document + submit — lands in PENDING_REVIEW. */
    protected UUID submittedExperience(Actor contributor) {
        return submittedExperience(contributor, Payloads.experience());
    }

    protected UUID submittedExperience(Actor contributor, JSONObject body) {
        UUID id = createDraft(contributor, body);
        addRound(contributor, id);
        uploadProof(contributor, id);
        ResponseEntity<String> response = post("/api/v1/experiences/" + id + "/submit", contributor, null);
        assertThat(statusOf(response))
                .as("submit fixture failed: %s", response.getBody())
                .isEqualTo(200);
        return id;
    }

    /** The full contributor-to-live path: submitted, then approved by the given admin. */
    protected UUID publishedExperience(Actor contributor, Actor admin) {
        return publishedExperience(contributor, admin, Payloads.experience());
    }

    protected UUID publishedExperience(Actor contributor, Actor admin, JSONObject body) {
        UUID id = submittedExperience(contributor, body);
        ResponseEntity<String> response = post("/api/v1/admin/experiences/" + id + "/approve", admin, null);
        assertThat(statusOf(response))
                .as("approve fixture failed: %s", response.getBody())
                .isEqualTo(200);
        return id;
    }

    // --- Reading an experience back ------------------------------------------------------------

    /** The raw {@code ExperienceView} union: {@code {entitled, teaser}} or {@code {entitled, full}}. */
    protected JSONObject viewOf(Actor viewer, UUID experienceId) {
        return jsonOf(get("/api/v1/experiences/" + experienceId, viewer));
    }

    /** The full write-up, asserting the caller was actually entitled to it. */
    protected JSONObject fullOf(Actor viewer, UUID experienceId) {
        JSONObject view = viewOf(viewer, experienceId);
        assertThat(view.getBoolean("entitled"))
                .as("expected full access to %s but got a teaser", experienceId)
                .isTrue();
        return view.getJSONObject("full");
    }

    /** The teaser, asserting the caller was NOT entitled to more. */
    protected JSONObject teaserOf(Actor viewer, UUID experienceId) {
        JSONObject view = viewOf(viewer, experienceId);
        assertThat(view.getBoolean("entitled"))
                .as("expected a teaser for %s but the caller got full access", experienceId)
                .isFalse();
        return view.getJSONObject("teaser");
    }

    protected String statusOfExperience(UUID experienceId) {
        return jdbc.queryForObject("SELECT status FROM experiences WHERE id = ?", String.class, experienceId);
    }

    // --- Payment fixtures ---------------------------------------------------------------------

    /**
     * Inserts the {@code CREATED} purchase row that a successful Razorpay order would have left
     * behind, and returns its order id. This is the seam that keeps the suite hermetic: order
     * creation is the one step that would require an outbound call, and everything downstream of
     * it — signature verification, entitlement granting, idempotency — runs for real.
     */
    protected String createdPurchase(Actor buyer, UUID experienceId) {
        return createdPurchase(buyer, experienceId, DEFAULT_PRICE_PAISE);
    }

    protected String createdPurchase(Actor buyer, UUID experienceId, long amountPaise) {
        String razorpayOrderId = Payloads.razorpayOrderId();
        purchaseRepository.save(
                new Purchase(UUID.randomUUID(), buyer.id(), experienceId, amountPaise, razorpayOrderId));
        return razorpayOrderId;
    }

    /**
     * The signature Razorpay Checkout would hand the browser: an HMAC-SHA256 of
     * {@code <order_id>|<payment_id>} under the key secret. {@code Utils.verifyPaymentSignature}
     * recomputes exactly this, so a signature built here exercises the real verification path
     * rather than bypassing it.
     */
    protected static String paymentSignature(String razorpayOrderId, String razorpayPaymentId) throws Exception {
        return Utils.getHash(razorpayOrderId + "|" + razorpayPaymentId, RAZORPAY_KEY_SECRET);
    }

    /** The signature Razorpay's servers send in {@code X-Razorpay-Signature}: an HMAC over the
     * exact raw request body under the webhook secret. */
    protected static String webhookSignature(String rawBody) throws Exception {
        return Utils.getHash(rawBody, RAZORPAY_WEBHOOK_SECRET);
    }

    /** Drives a buyer all the way to a real, paid entitlement through the public API. */
    protected void purchase(Actor buyer, UUID experienceId) throws Exception {
        String orderId = createdPurchase(buyer, experienceId);
        String paymentId = Payloads.razorpayPaymentId();
        ResponseEntity<String> response = post("/api/v1/purchases/confirm", buyer, new JSONObject()
                .put("razorpayOrderId", orderId)
                .put("razorpayPaymentId", paymentId)
                .put("razorpaySignature", paymentSignature(orderId, paymentId)));
        assertThat(statusOf(response))
                .as("purchase fixture failed: %s", response.getBody())
                .isEqualTo(200);
    }

    // --- Direct database reads (assertions only, never setup) ------------------------------

    protected long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    protected long countRows(String table, String whereClause, Object... args) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + whereClause, Long.class, args);
        return count == null ? 0 : count;
    }

    protected Path proofDirectory() {
        return PROOF_DIR;
    }
}
