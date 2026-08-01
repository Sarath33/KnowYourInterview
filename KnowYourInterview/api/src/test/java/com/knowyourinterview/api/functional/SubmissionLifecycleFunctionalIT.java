package com.knowyourinterview.api.functional;

import java.util.List;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-SUB — the contributor's submission lifecycle, every transition and every guard. See
 * {@code docs/09-test-plan.md} §7.5.
 *
 * <p>The state machine here is the product: draft → pending review → published, with rejection,
 * correction, unpublish and delete branching off it. Each transition carries a rule about what
 * else must be true, and those rules are what stop unreviewed content going live (a legal
 * exposure, per open item #1 in the handoff) and stop a contributor destroying data someone has
 * already paid for.
 */
class SubmissionLifecycleFunctionalIT extends FunctionalTestBase {

    // --- Creation ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-01: a new draft starts unpublished at the platform price")
    void newDraftStartsAsAnUnpublishedPlatformPricedExperience() {
        Actor contributor = registerUser();

        ResponseEntity<String> response = post("/api/v1/experiences", contributor, Payloads.experience());

        assertThat(statusOf(response)).isEqualTo(201);
        JSONObject draft = jsonOf(response);
        assertThat(draft.getString("status")).isEqualTo("DRAFT");
        assertThat(draft.getLong("pricePaise")).isEqualTo(DEFAULT_PRICE_PAISE);
        assertThat(draft.getBoolean("isFree")).isFalse();
        assertThat(draft.isNull("publishedAt")).isTrue();
        assertThat(draft.getString("contributorId")).isEqualTo(contributor.id().toString());
        assertThat(draft.getJSONArray("rounds")).isEmpty();
        assertThat(draft.getJSONArray("proofDocuments")).isEmpty();
        assertThat(draft.getLong("viewCount")).isZero();
        assertThat(draft.getLong("unlockCount")).isZero();
    }

    @Test
    @DisplayName("FT-SUB-02: a contributor cannot set their own price")
    void contributorCannotSetTheirOwnPrice() {
        Actor contributor = registerUser();

        // Pricing is platform-set by design (Phase 0). A client that sends a price must not be
        // able to make their experience free — or make it cost ₹10,000.
        JSONObject body = Payloads.experience().put("pricePaise", 1).put("isFree", true);
        UUID id = createDraft(contributor, body);

        JSONObject created = fullOf(contributor, id);
        assertThat(created.getLong("pricePaise")).isEqualTo(DEFAULT_PRICE_PAISE);
        assertThat(created.getBoolean("isFree")).isFalse();
    }

    @Test
    @DisplayName("FT-SUB-03: creation validation rejects out-of-range and missing fields")
    void creationValidationRejectsBadInput() {
        Actor contributor = registerUser();

        record Invalid(String field, JSONObject body) {
        }
        List<Invalid> invalid = List.of(
                new Invalid("company", Payloads.experience().put("company", "")),
                new Invalid("teaser", Payloads.experience().put("teaser", "   ")),
                new Invalid("roleTitle", Payloads.experience().put("roleTitle", "")),
                new Invalid("outcome", Payloads.experience().put("outcome", JSONObject.NULL)),
                new Invalid("interviewMonth", Payloads.experience().put("interviewMonth", 13)),
                new Invalid("interviewYear", Payloads.experience().put("interviewYear", 1999)),
                new Invalid("overallDifficulty", Payloads.experience().put("overallDifficulty", 6)));

        for (Invalid testCase : invalid) {
            ResponseEntity<String> response = post("/api/v1/experiences", contributor, testCase.body());

            assertThat(statusOf(response))
                    .as("expected 400 for invalid %s", testCase.field())
                    .isEqualTo(400);
            assertThat(jsonOf(response).getJSONObject("fieldErrors").keySet())
                    .as("fieldErrors should name %s", testCase.field())
                    .contains(testCase.field());
        }
        assertThat(countRows("experiences")).isZero();
    }

    @Test
    @DisplayName("FT-SUB-04: a malformed body is a 400, never a 500")
    void malformedBodyIsRejectedCleanly() {
        Actor contributor = registerUser();

        ResponseEntity<String> badJson = exchange(HttpMethod.POST, "/api/v1/experiences",
                headers(contributor.accessToken()), "{\"company\": \"Acme\", ");
        ResponseEntity<String> badEnum = post("/api/v1/experiences", contributor,
                Payloads.experience().put("outcome", "GHOSTED"));

        assertThat(statusOf(badJson)).isEqualTo(400);
        assertThat(messageOf(badJson)).isEqualTo("Malformed request body");
        assertThat(statusOf(badEnum)).isEqualTo(400);
        assertThat(countRows("experiences")).isZero();
    }

    // --- Rounds -----------------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-05/06/07: rounds are numbered in order, editable in place, and removable")
    void roundsAreNumberedEditableAndRemovable() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        addRound(contributor, experienceId, Payloads.round("PHONE_SCREEN"));
        UUID second = addRound(contributor, experienceId, Payloads.round("SYSTEM_DESIGN"));
        addRound(contributor, experienceId, Payloads.round("HIRING_MANAGER"));

        JSONArray rounds = fullOf(contributor, experienceId).getJSONArray("rounds");
        assertThat(rounds.length()).isEqualTo(3);
        assertThat(rounds.getJSONObject(0).getInt("roundNumber")).isEqualTo(1);
        assertThat(rounds.getJSONObject(1).getInt("roundNumber")).isEqualTo(2);
        assertThat(rounds.getJSONObject(2).getInt("roundNumber")).isEqualTo(3);
        assertThat(rounds.getJSONObject(1).getString("roundType")).isEqualTo("SYSTEM_DESIGN");
        // Tags are stored comma-joined and split back out — a round trip worth pinning.
        assertThat(rounds.getJSONObject(0).getJSONArray("topicsTags").toList())
                .containsExactly("system-design", "distributed-systems");

        // FT-SUB-06: edit in place keeps the identity and position of the round.
        ResponseEntity<String> updated = put("/api/v1/experiences/" + experienceId + "/rounds/" + second, contributor,
                Payloads.round("SYSTEM_DESIGN").put("questionsAsked", "Design a URL shortener."));
        assertThat(statusOf(updated)).isEqualTo(200);
        assertThat(jsonOf(updated).getString("id")).isEqualTo(second.toString());
        assertThat(jsonOf(updated).getInt("roundNumber")).isEqualTo(2);
        assertThat(jsonOf(updated).getString("questionsAsked")).isEqualTo("Design a URL shortener.");

        // FT-SUB-07: delete.
        assertThat(statusOf(delete("/api/v1/experiences/" + experienceId + "/rounds/" + second, contributor)))
                .isEqualTo(204);
        assertThat(fullOf(contributor, experienceId).getJSONArray("rounds").length()).isEqualTo(2);
    }

    @Test
    @DisplayName("FT-SUB-08: a round can't be reached through a different experience's path")
    void roundIsScopedToItsOwnExperience() {
        Actor contributor = registerUser();
        UUID first = createDraft(contributor);
        UUID second = createDraft(contributor);
        UUID roundOnFirst = addRound(contributor, first);

        assertThat(statusOf(put("/api/v1/experiences/" + second + "/rounds/" + roundOnFirst,
                contributor, Payloads.round()))).isEqualTo(404);
        // Delete is a no-op rather than a 404 (deleteByIdAndExperienceId matches nothing), but
        // what matters is that the round on the other experience survives.
        delete("/api/v1/experiences/" + second + "/rounds/" + roundOnFirst, contributor);
        assertThat(fullOf(contributor, first).getJSONArray("rounds").length()).isEqualTo(1);
    }

    // --- Submission guards -------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-09: submission is blocked with no rounds")
    void submissionRequiresAtLeastOneRound() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);
        uploadProof(contributor, experienceId);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("at least one interview round");
        assertThat(statusOfExperience(experienceId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("FT-SUB-10: submission is blocked with no proof document")
    void submissionRequiresProof() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);
        addRound(contributor, experienceId);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        // Proof is the entire basis of "verified" in a verified marketplace — an admin has
        // nothing to check against without it, and the contributor gets paid on publish.
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("at least one proof document");
        assertThat(statusOfExperience(experienceId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("FT-SUB-11: a complete submission enters the review queue")
    void completeSubmissionEntersTheReviewQueue() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(contributor);
        addRound(contributor, experienceId);
        uploadProof(contributor, experienceId);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(jsonOf(response).getString("status")).isEqualTo("PENDING_REVIEW");

        JSONArray queue = jsonArrayOf(get("/api/v1/admin/experiences", admin));
        assertThat(queue.length()).isEqualTo(1);
        assertThat(queue.getJSONObject(0).getString("id")).isEqualTo(experienceId.toString());
        // Not visible to the public until an admin publishes it.
        assertThat(jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items")).isEmpty();
    }

    @Test
    @DisplayName("FT-SUB-12: content stays editable while a submission is under review")
    void contentStaysEditableWhilePendingReview() {
        Actor contributor = registerUser();
        UUID experienceId = submittedExperience(contributor);

        // Deliberate (requireContentEditable includes PENDING_REVIEW): a contributor spotting a
        // typo shouldn't have to wait for a rejection to fix it.
        assertThat(statusOf(put("/api/v1/experiences/" + experienceId, contributor,
                Payloads.experience().put("teaser", "Corrected teaser while under review.")))).isEqualTo(200);
        assertThat(statusOf(post("/api/v1/experiences/" + experienceId + "/rounds", contributor, Payloads.round())))
                .isEqualTo(201);
        assertThat(statusOfExperience(experienceId)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("FT-SUB-13: an already-pending submission can't be resubmitted")
    void pendingSubmissionCannotBeResubmitted() {
        Actor contributor = registerUser();
        UUID experienceId = submittedExperience(contributor);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(statusOfExperience(experienceId)).isEqualTo("PENDING_REVIEW");
    }

    // --- Verdicts and resubmission -------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-14: resubmitting after a rejection clears the stale reason")
    void resubmissionAfterRejectionClearsTheReason() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        post("/api/v1/admin/experiences/" + experienceId + "/reject", admin,
                new JSONObject().put("reason", "The proof document is illegible."));
        assertThat(fullOf(contributor, experienceId).getString("rejectionReason"))
                .isEqualTo("The proof document is illegible.");

        uploadProof(contributor, experienceId, "clearer-proof.pdf", "application/pdf", "clearer".getBytes());
        ResponseEntity<String> resubmitted =
                post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        assertThat(statusOf(resubmitted)).isEqualTo(200);
        JSONObject body = jsonOf(resubmitted);
        assertThat(body.getString("status")).isEqualTo("PENDING_REVIEW");
        // A fresh reviewer must not be shown last round's verdict as if it still applied.
        assertThat(body.isNull("rejectionReason")).isTrue();
    }

    @Test
    @DisplayName("FT-SUB-15: resubmitting after a correction request clears the notes")
    void resubmissionAfterCorrectionClearsTheNotes() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = submittedExperience(contributor);

        post("/api/v1/admin/experiences/" + experienceId + "/request-correction", admin,
                new JSONObject().put("notes", "Please add the take-home round."));
        JSONObject afterRequest = fullOf(contributor, experienceId);
        assertThat(afterRequest.getString("status")).isEqualTo("CORRECTION_REQUESTED");
        assertThat(afterRequest.getString("correctionNotes")).isEqualTo("Please add the take-home round.");

        addRound(contributor, experienceId, Payloads.round("TAKE_HOME"));
        ResponseEntity<String> resubmitted =
                post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        assertThat(statusOf(resubmitted)).isEqualTo(200);
        assertThat(jsonOf(resubmitted).getString("status")).isEqualTo("PENDING_REVIEW");
        assertThat(jsonOf(resubmitted).isNull("correctionNotes")).isTrue();
    }

    // --- Published is locked -------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-16: a published experience is locked until it's unpublished")
    void publishedContentIsLocked() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);
        UUID roundId = UUID.fromString(
                fullOf(contributor, experienceId).getJSONArray("rounds").getJSONObject(0).getString("id"));
        UUID proofId = UUID.fromString(
                fullOf(contributor, experienceId).getJSONArray("proofDocuments").getJSONObject(0).getString("id"));

        // Live content that people are paying for can't be silently rewritten underneath them.
        List<ResponseEntity<String>> blocked = List.of(
                put("/api/v1/experiences/" + experienceId, contributor,
                        Payloads.experience().put("company", "Rewritten After Sale")),
                post("/api/v1/experiences/" + experienceId + "/rounds", contributor, Payloads.round()),
                put("/api/v1/experiences/" + experienceId + "/rounds/" + roundId, contributor, Payloads.round()),
                delete("/api/v1/experiences/" + experienceId + "/rounds/" + roundId, contributor),
                delete("/api/v1/experiences/" + experienceId + "/proof/" + proofId, contributor),
                delete("/api/v1/experiences/" + experienceId, contributor));

        for (ResponseEntity<String> response : blocked) {
            assertThat(statusOf(response)).isEqualTo(400);
            assertThat(messageOf(response)).contains("unpublish it first");
        }

        ResponseEntity<String> upload = postFile("/api/v1/experiences/" + experienceId + "/proof",
                contributor, "extra.pdf", "application/pdf", "extra".getBytes());
        assertThat(statusOf(upload)).isEqualTo(400);

        JSONObject unchanged = fullOf(contributor, experienceId);
        assertThat(unchanged.getString("company")).isEqualTo("Acme Corp");
        assertThat(unchanged.getString("status")).isEqualTo("PUBLISHED");
        assertThat(unchanged.getJSONArray("rounds").length()).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-SUB-17: unpublishing returns an experience to draft and pulls it from browse")
    void unpublishReturnsToDraft() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);
        assertThat(jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items").length()).isEqualTo(1);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/unpublish", contributor, null);

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(jsonOf(response).getString("status")).isEqualTo("DRAFT");
        assertThat(jsonOf(response).isNull("publishedAt")).isTrue();
        assertThat(jsonOf(getAnonymously("/api/v1/experiences")).getJSONArray("items")).isEmpty();
        // ...and it's editable again.
        assertThat(statusOf(put("/api/v1/experiences/" + experienceId, contributor,
                Payloads.experience().put("company", "Corrected Corp")))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-SUB-18: only a published experience can be unpublished")
    void unpublishOnlyAppliesToPublished() {
        Actor contributor = registerUser();
        UUID draftId = createDraft(contributor);

        ResponseEntity<String> response = post("/api/v1/experiences/" + draftId + "/unpublish", contributor, null);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("Only a published experience can be unpublished");
    }

    @Test
    @DisplayName("FT-SUB-19: a purchaser keeps full access after the experience is unpublished")
    void purchaserKeepsAccessThroughAnUnpublish() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        purchase(buyer, experienceId);

        post("/api/v1/experiences/" + experienceId + "/unpublish", contributor, null);

        // Someone who paid must not lose what they paid for because the contributor pulled the
        // listing to fix a typo. Entitlement grants visibility in its own right, independent of
        // the experience's current status.
        JSONObject stillVisible = fullOf(buyer, experienceId);
        assertThat(stillVisible.getString("status")).isEqualTo("DRAFT");
        assertThat(stillVisible.getJSONArray("rounds").length()).isEqualTo(1);
        // A non-purchaser, meanwhile, can no longer see it at all.
        assertThat(statusOf(get("/api/v1/experiences/" + experienceId, registerUser()))).isEqualTo(404);
    }

    // --- Deletion ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-20: deleting a draft removes its rounds, proof rows and stored files")
    void deletingADraftCleansUpEverything() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);
        addRound(contributor, experienceId);
        uploadProof(contributor, experienceId);
        put("/api/v1/experiences/" + experienceId, contributor, Payloads.experience().put("company", "Edited"));

        assertThat(statusOf(delete("/api/v1/experiences/" + experienceId, contributor))).isEqualTo(204);

        assertThat(countRows("experiences")).isZero();
        assertThat(countRows("experience_rounds")).isZero();
        assertThat(countRows("proof_documents")).isZero();
        assertThat(countRows("experience_edit_snapshots")).isZero();
        // The file has to go too: proof documents are sensitive PII (open item #3), so an
        // orphaned copy on disk after a delete is a retention problem, not just untidiness.
        java.io.File experienceDir = proofDirectory().resolve(experienceId.toString()).toFile();
        java.io.File[] leftovers = experienceDir.listFiles();
        assertThat(leftovers == null || leftovers.length == 0)
                .as("proof files should be gone from disk after the delete")
                .isTrue();
    }

    @Test
    @DisplayName("FT-SUB-21: an experience that has been purchased can't be deleted")
    void purchasedExperienceCannotBeDeleted() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        purchase(buyer, experienceId);
        post("/api/v1/experiences/" + experienceId + "/unpublish", contributor, null);

        ResponseEntity<String> response = delete("/api/v1/experiences/" + experienceId, contributor);

        // Deleting this would strip a paying viewer of what they bought. A clear 400 beats the
        // opaque 409 a raw foreign-key violation would produce.
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("has been purchased");
        assertThat(countRows("experiences")).isEqualTo(1);
        assertThat(countRows("entitlements")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-SUB-22: an experience with a payout on record can't be deleted")
    void experienceWithAPayoutCannotBeDeleted() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);
        post("/api/v1/experiences/" + experienceId + "/unpublish", contributor, null);

        ResponseEntity<String> response = delete("/api/v1/experiences/" + experienceId, contributor);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("payout on record");
        assertThat(countRows("payouts")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-SUB-23: a free contribution that someone viewed can still be deleted (§1.1)")
    void viewedFreeContributionCanBeDeleted() {
        Actor contributor = registerUser();
        Actor viewer = registerUser();
        UUID experienceId = createDraft(contributor, Payloads.freeContribution());
        addRound(contributor, experienceId);
        post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        // Signed-in view records an experience_views row.
        assertThat(statusOf(get("/api/v1/experiences/" + experienceId, viewer))).isEqualTo(200);
        assertThat(countRows("experience_views")).isEqualTo(1);

        post("/api/v1/experiences/" + experienceId + "/unpublish", contributor, null);
        ResponseEntity<String> response = delete("/api/v1/experiences/" + experienceId, contributor);

        // The §1.1 regression: experience_views' FK doesn't cascade, and neither the entitlement
        // nor the payout guard catches a free contribution, so this used to fail on a raw
        // constraint violation surfaced as an opaque 409.
        assertThat(statusOf(response))
                .as("expected a clean delete; a 409 here means the experience_views cleanup regressed")
                .isEqualTo(204);
        assertThat(countRows("experience_views")).isZero();
        assertThat(countRows("experiences")).isZero();
    }

    // --- Free and reference submissions ---------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-24: a free contribution publishes immediately, with no proof and no payout")
    void freeContributionPublishesWithoutReview() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(contributor, Payloads.freeContribution());
        addRound(contributor, experienceId);

        // Note: no proof upload. Proof exists for an admin to verify against, and nobody
        // reviews a free contribution, so requiring it would be ceremony.
        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject published = jsonOf(response);
        assertThat(published.getString("status")).isEqualTo("PUBLISHED");
        assertThat(published.getBoolean("isFree")).isTrue();
        assertThat(published.getLong("pricePaise")).isZero();
        assertThat(published.isNull("publishedAt")).isFalse();
        // No admin ever saw it, so there's no review log — and no money, so no payout.
        assertThat(countRows("review_logs")).isZero();
        assertThat(countRows("payouts")).isZero();
        assertThat(jsonArrayOf(get("/api/v1/admin/experiences", admin))).isEmpty();
    }

    @Test
    @DisplayName("FT-SUB-25: only an admin can submit a reference to a public source")
    void referenceSubmissionIsAdminOnly() {
        Actor contributor = registerUser();

        ResponseEntity<String> response = post("/api/v1/experiences", contributor, Payloads.referenceSubmission());

        // The UI hides this option from non-admins, but hiding is not enforcing — a hand-rolled
        // request must be refused server-side too.
        assertThat(statusOf(response)).isEqualTo(403);
        assertThat(countRows("experiences")).isZero();
    }

    @Test
    @DisplayName("FT-SUB-26: a reference submission needs a source name")
    void referenceSubmissionRequiresASourceName() {
        Actor admin = registerAdmin();

        ResponseEntity<String> response = post("/api/v1/experiences", admin,
                Payloads.referenceSubmission().put("sourceName", ""));

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("Source site/platform is required");
    }

    @Test
    @DisplayName("FT-SUB-27: a reference submission is free but still goes through review")
    void referenceSubmissionIsFreeButStillReviewed() {
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(admin, Payloads.referenceSubmission());
        addRound(admin, experienceId);
        uploadProof(admin, experienceId);

        ResponseEntity<String> response = post("/api/v1/experiences/" + experienceId + "/submit", admin, null);

        JSONObject submitted = jsonOf(response);
        assertThat(submitted.getString("status"))
                .as("unlike a free contribution, a reference submission is still reviewed")
                .isEqualTo("PENDING_REVIEW");
        assertThat(submitted.getBoolean("isFree")).isTrue();
        assertThat(submitted.getLong("pricePaise")).isZero();
        assertThat(submitted.getString("sourceName")).isEqualTo("Example Blog");
    }

    @Test
    @DisplayName("FT-SUB-28: source fields are immutable after creation")
    void sourceFieldsCannotBeEditedLater() {
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(admin, Payloads.referenceSubmission());

        put("/api/v1/experiences/" + experienceId, admin, Payloads.experience()
                .put("sourceUrl", "https://attacker.example/other")
                .put("sourceName", "Somewhere Else"));

        JSONObject unchanged = fullOf(admin, experienceId);
        assertThat(unchanged.getString("sourceUrl")).isEqualTo("https://example.com/some-public-writeup");
        assertThat(unchanged.getString("sourceName")).isEqualTo("Example Blog");
        assertThat(unchanged.getBoolean("isFree")).isTrue();
    }

    @Test
    @DisplayName("FT-SUB-28b: freeContribution can't be switched on by editing a paid draft")
    void freeContributionCannotBeTurnedOnByEditing() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        put("/api/v1/experiences/" + experienceId, contributor, Payloads.experience().put("freeContribution", true));

        // Otherwise a contributor could create a normal draft, flip it free on edit, and publish
        // without any admin ever seeing it.
        JSONObject unchanged = fullOf(contributor, experienceId);
        assertThat(unchanged.getBoolean("isFree")).isFalse();
        assertThat(unchanged.getLong("pricePaise")).isEqualTo(DEFAULT_PRICE_PAISE);
    }

    // --- Edit history --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-29: an edit is snapshotted with a diff of what changed")
    void editHistoryRecordsWhatChanged() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        put("/api/v1/experiences/" + experienceId, contributor, Payloads.experience()
                .put("company", "Globex")
                .put("teaser", "A completely rewritten teaser."));

        JSONArray history = jsonArrayOf(get("/api/v1/experiences/" + experienceId + "/history", contributor));
        assertThat(history.length()).isEqualTo(1);
        JSONObject entry = history.getJSONObject(0);
        assertThat(entry.getJSONArray("changedFields").toList()).containsExactlyInAnyOrder("Company", "Teaser");
        // The snapshot holds the values as they were BEFORE the edit.
        assertThat(entry.getString("company")).isEqualTo("Acme Corp");
    }

    @Test
    @DisplayName("FT-SUB-30: re-saving an unchanged form records no history entry")
    void noOpEditIsNotRecorded() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        put("/api/v1/experiences/" + experienceId, contributor, Payloads.experience());
        put("/api/v1/experiences/" + experienceId, contributor, Payloads.experience());

        assertThat(jsonArrayOf(get("/api/v1/experiences/" + experienceId + "/history", contributor))).isEmpty();
        assertThat(countRows("experience_edit_snapshots")).isZero();
    }

    // --- Scoping ------------------------------------------------------------------------------

    @Test
    @DisplayName("FT-SUB-31: /experiences/mine only ever returns the caller's own submissions")
    void mineIsScopedToTheCaller() {
        Actor first = registerUser();
        Actor second = registerUser();
        UUID firstsDraft = createDraft(first, Payloads.experience("First Corp", "Engineer", "First teaser."));
        UUID secondsDraft = createDraft(second, Payloads.experience("Second Corp", "Engineer", "Second teaser."));

        JSONArray firstsList = jsonArrayOf(get("/api/v1/experiences/mine", first));
        JSONArray secondsList = jsonArrayOf(get("/api/v1/experiences/mine", second));

        assertThat(firstsList.length()).isEqualTo(1);
        assertThat(firstsList.getJSONObject(0).getString("id")).isEqualTo(firstsDraft.toString());
        assertThat(secondsList.length()).isEqualTo(1);
        assertThat(secondsList.getJSONObject(0).getString("id")).isEqualTo(secondsDraft.toString());
    }
}
