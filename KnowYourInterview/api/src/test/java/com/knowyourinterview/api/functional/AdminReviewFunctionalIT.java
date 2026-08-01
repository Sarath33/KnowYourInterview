package com.knowyourinterview.api.functional;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-REVIEW — the admin review queue and its three verdicts. See {@code docs/09-test-plan.md}
 * §7.9.
 *
 * <p>Approval is the single most consequential action in the product: it publishes content
 * (which is where the NDA exposure in open item #1 becomes real), stamps a price, and creates a
 * payout liability. Everything here is about that action being taken deliberately, exactly once,
 * on the right thing.
 */
class AdminReviewFunctionalIT extends FunctionalTestBase {

    // --- The queue ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-REVIEW-01: the queue holds only pending submissions, oldest first")
    void queueHoldsOnlyPendingSubmissionsOldestFirst() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();

        UUID firstPending = submittedExperience(contributor, Payloads.experience("First", "Engineer", "1."));
        UUID secondPending = submittedExperience(contributor, Payloads.experience("Second", "Engineer", "2."));
        createDraft(contributor, Payloads.experience("StillDraft", "Engineer", "3."));
        publishedExperience(contributor, admin, Payloads.experience("AlreadyLive", "Engineer", "4."));

        JSONArray queue = jsonArrayOf(get("/api/v1/admin/experiences", admin));

        assertThat(queue.length()).isEqualTo(2);
        // Oldest first, so the queue is a fair FIFO rather than whatever the database felt like.
        assertThat(queue.getJSONObject(0).getString("id")).isEqualTo(firstPending.toString());
        assertThat(queue.getJSONObject(1).getString("id")).isEqualTo(secondPending.toString());
    }

    @Test
    @DisplayName("FT-REVIEW-02: the queue carries everything an admin needs to actually judge it")
    void queueCarriesTheFullSubstanceOfTheSubmission() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(contributor, Payloads.experience()
                .put("confidentialNote", "The recruiter mentioned an unannounced reorg."));
        addRound(contributor, experienceId, Payloads.round("PHONE_SCREEN"));
        addRound(contributor, experienceId, Payloads.round("SYSTEM_DESIGN"));
        uploadProof(contributor, experienceId);
        post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        JSONObject queued = jsonArrayOf(get("/api/v1/admin/experiences", admin)).getJSONObject(0);

        // The admin is verifying content and creating a payout liability off the back of it, so
        // the rounds themselves have to be in the payload — not just a count of them.
        JSONArray rounds = queued.getJSONArray("rounds");
        assertThat(rounds.length()).isEqualTo(2);
        assertThat(rounds.getJSONObject(0).getString("questionsAsked")).isNotBlank();
        assertThat(rounds.getJSONObject(0).getString("approach")).isNotBlank();
        assertThat(queued.getJSONArray("proofDocuments").length()).isEqualTo(1);
        assertThat(queued.getString("confidentialNote")).contains("unannounced reorg");
        // Note: the API returning this is necessary but not sufficient — docs/09-test-plan.md
        // gap G1 covers the finding that the review UI never renders the rounds.
    }

    @Test
    @DisplayName("FT-REVIEW-15: fetching an unknown experience for review is a 404")
    void unknownExperienceForReviewIs404() {
        Actor admin = registerAdmin();

        assertThat(statusOf(get("/api/v1/admin/experiences/" + UUID.randomUUID(), admin))).isEqualTo(404);
    }

    // --- Approve -----------------------------------------------------------------------------

    @Test
    @DisplayName("FT-REVIEW-03: approval publishes, logs the decision, and books the payout")
    void approvalPublishesLogsAndBooksThePayout() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> response = post("/api/v1/admin/experiences/" + experienceId + "/approve", admin, null);

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject published = jsonOf(response);
        assertThat(published.getString("status")).isEqualTo("PUBLISHED");
        assertThat(published.isNull("publishedAt")).isFalse();

        // Audit trail.
        assertThat(countRows("review_logs", "experience_id = ? AND action = 'APPROVED' AND admin_id = ?",
                experienceId, admin.id())).isEqualTo(1);

        // Payout ledger: one row, PENDING, at the configured flat fee.
        assertThat(countRows("payouts")).isEqualTo(1);
        Long amount = jdbc.queryForObject(
                "SELECT amount_paise FROM payouts WHERE experience_id = ?", Long.class, experienceId);
        assertThat(amount).isEqualTo(CONTRIBUTOR_PAYOUT_PAISE);
        assertThat(jdbc.queryForObject("SELECT status FROM payouts WHERE experience_id = ?",
                String.class, experienceId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT contributor_id::text FROM payouts WHERE experience_id = ?",
                String.class, experienceId)).isEqualTo(contributor.id().toString());

        // And it's now publicly browsable.
        assertThat(jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items").length()).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-REVIEW-04: approving twice can't double-book a payout")
    void approvingTwiceCannotDoubleBookAPayout() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);
        post("/api/v1/admin/experiences/" + experienceId + "/approve", admin, null);

        ResponseEntity<String> second = post("/api/v1/admin/experiences/" + experienceId + "/approve", admin, null);

        // A double-click on "Approve & publish" must not owe the contributor twice.
        assertThat(statusOf(second)).isEqualTo(400);
        assertThat(messageOf(second)).contains("Only a pending-review experience can be approved");
        assertThat(countRows("payouts")).isEqualTo(1);
        assertThat(countRows("review_logs")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-REVIEW-05: only a pending submission can be approved")
    void onlyPendingSubmissionsCanBeApproved() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID draft = createDraft(contributor);

        ResponseEntity<String> response = post("/api/v1/admin/experiences/" + draft + "/approve", admin, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(statusOfExperience(draft)).isEqualTo("DRAFT");
        assertThat(countRows("payouts")).isZero();
    }

    @Test
    @DisplayName("FT-REVIEW-06: approving a free reference submission books no payout")
    void approvingAFreeReferenceBooksNoPayout() {
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(admin, Payloads.referenceSubmission());

        ResponseEntity<String> response = post("/api/v1/admin/experiences/" + experienceId + "/approve", admin, null);

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(jsonOf(response).getString("status")).isEqualTo("PUBLISHED");
        assertThat(jsonOf(response).getBoolean("isFree")).isTrue();
        // There is no revenue behind a free reference, so there is nothing to pay out of.
        assertThat(countRows("payouts")).isZero();
        // The decision is still logged — it was still a human judgement.
        assertThat(countRows("review_logs", "action = 'APPROVED'")).isEqualTo(1);
    }

    // --- Reject ------------------------------------------------------------------------------

    @Test
    @DisplayName("FT-REVIEW-07/09: rejection records the reason and books nothing")
    void rejectionRecordsTheReasonAndBooksNothing() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> response = post("/api/v1/admin/experiences/" + experienceId + "/reject", admin,
                new JSONObject().put("reason", "The proof document doesn't match the stated company."));

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject rejected = jsonOf(response);
        assertThat(rejected.getString("status")).isEqualTo("REJECTED");
        assertThat(rejected.getString("rejectionReason")).contains("doesn't match the stated company");
        assertThat(countRows("review_logs", "action = 'REJECTED'")).isEqualTo(1);
        assertThat(countRows("payouts")).isZero();
        // Not published, so not browsable.
        assertThat(jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items")).isEmpty();
    }

    @Test
    @DisplayName("FT-REVIEW-08: a rejection must carry a reason")
    void rejectionRequiresAReason() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> blank = post("/api/v1/admin/experiences/" + experienceId + "/reject", admin,
                new JSONObject().put("reason", "   "));
        ResponseEntity<String> missing = post("/api/v1/admin/experiences/" + experienceId + "/reject", admin,
                new JSONObject());

        // A contributor who gets rejected with no explanation has nothing to act on, and the
        // review log becomes useless as an audit trail.
        assertThat(statusOf(blank)).isEqualTo(400);
        assertThat(jsonOf(blank).getJSONObject("fieldErrors").keySet()).contains("reason");
        assertThat(statusOf(missing)).isEqualTo(400);
        assertThat(statusOfExperience(experienceId)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("FT-REVIEW-05b: only a pending submission can be rejected")
    void onlyPendingSubmissionsCanBeRejected() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID published = publishedExperience(contributor, admin);

        ResponseEntity<String> response = post("/api/v1/admin/experiences/" + published + "/reject", admin,
                new JSONObject().put("reason", "changed my mind"));

        // Un-publishing live content is deliberately a different action with different
        // consequences — reject must not be a back door into it.
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(statusOfExperience(published)).isEqualTo("PUBLISHED");
    }

    // --- Request correction ---------------------------------------------------------------------

    @Test
    @DisplayName("FT-REVIEW-10: a correction request records notes and books nothing")
    void correctionRequestRecordsNotes() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> response = post(
                "/api/v1/admin/experiences/" + experienceId + "/request-correction", admin,
                new JSONObject().put("notes", "Please name the level and add the take-home round."));

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject corrected = jsonOf(response);
        assertThat(corrected.getString("status")).isEqualTo("CORRECTION_REQUESTED");
        assertThat(corrected.getString("correctionNotes")).contains("take-home round");
        assertThat(countRows("review_logs", "action = 'CORRECTION_REQUESTED'")).isEqualTo(1);
        assertThat(countRows("payouts")).isZero();
    }

    @Test
    @DisplayName("FT-REVIEW-11: a correction request must carry notes")
    void correctionRequestRequiresNotes() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> response = post(
                "/api/v1/admin/experiences/" + experienceId + "/request-correction", admin,
                new JSONObject().put("notes", ""));

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(jsonOf(response).getJSONObject("fieldErrors").keySet()).contains("notes");
        assertThat(statusOfExperience(experienceId)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("FT-REVIEW-12: an admin can fix a submission directly, and it shows in the history")
    void adminCanEditDuringReview() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> edit = put("/api/v1/experiences/" + experienceId, admin,
                Payloads.experience().put("company", "Acme Corporation"));

        assertThat(statusOf(edit)).isEqualTo(200);
        assertThat(jsonOf(edit).getString("company")).isEqualTo("Acme Corporation");
        // The contributor can see what was changed on their behalf — an admin edit is not a
        // silent rewrite, it lands in the same edit history their own edits do.
        JSONArray history = jsonArrayOf(get("/api/v1/experiences/" + experienceId + "/history", contributor));
        assertThat(history.length()).isEqualTo(1);
        assertThat(history.getJSONObject(0).getJSONArray("changedFields").toList()).contains("Company");
        assertThat(history.getJSONObject(0).getString("company")).isEqualTo("Acme Corp");
    }

    @Test
    @DisplayName("FT-REVIEW-14: the review log keeps every verdict, not just the last one")
    void reviewLogKeepsEveryVerdict() {
        Actor contributor = registerUser();
        Actor firstAdmin = registerAdmin();
        Actor secondAdmin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        post("/api/v1/admin/experiences/" + experienceId + "/reject", firstAdmin,
                new JSONObject().put("reason", "Proof is illegible."));
        post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);
        post("/api/v1/admin/experiences/" + experienceId + "/approve", secondAdmin, null);

        // Deliberately non-cascading (unlike rounds and proof) — this is the audit trail for
        // decisions that moved money and published content, and it has to survive.
        assertThat(countRows("review_logs", "experience_id = ?", experienceId)).isEqualTo(2);
        assertThat(countRows("review_logs", "action = 'REJECTED' AND admin_id = ?", firstAdmin.id())).isEqualTo(1);
        assertThat(countRows("review_logs", "action = 'APPROVED' AND admin_id = ?", secondAdmin.id())).isEqualTo(1);
        assertThat(countRows("payouts")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-REVIEW-13: re-approving after an unpublish must not owe the contributor twice")
    void reapprovalAfterUnpublishDoesNotDoublePay() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);
        assertThat(countRows("payouts")).isEqualTo(1);

        post("/api/v1/experiences/" + experienceId + "/unpublish", contributor, null);
        put("/api/v1/experiences/" + experienceId, contributor,
                Payloads.experience().put("teaser", "Corrected teaser, resubmitted for review."));
        post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        ResponseEntity<String> reapproval =
                post("/api/v1/admin/experiences/" + experienceId + "/approve", admin, null);

        // The money invariant is the hard requirement, and it holds either way: payouts has a
        // UNIQUE constraint on experience_id, so a second ledger row is impossible.
        assertThat(countRows("payouts")).as("a contributor must never be owed twice for one experience")
                .isEqualTo(1);

        // The usability half is the open question — see docs/09-test-plan.md gap G5. If this
        // assertion fails with 409, that is a genuine finding, not a broken test:
        // AdminReviewService#approve unconditionally inserts a payout, so the second insert
        // violates the constraint, the whole transaction rolls back, and a legitimately
        // re-reviewed experience can never be published again. The fix is a "payout already
        // exists for this experience" check in approve(), not a change to this test.
        assertThat(statusOf(reapproval))
                .as("re-approving a corrected experience should republish it; a 409 here is finding G5")
                .isEqualTo(200);
        assertThat(statusOfExperience(experienceId)).isEqualTo("PUBLISHED");
    }
}
