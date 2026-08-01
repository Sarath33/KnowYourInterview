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

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-VIEW — the single-experience detail endpoint: who gets the teaser, who gets the full
 * write-up, and how views are counted. See {@code docs/09-test-plan.md} §7.8.
 *
 * <p>{@code getPublicView} is the paywall. It is also the only place {@code view_count} is
 * written, which makes an ordinary-looking GET a write transaction — the source of the §1.2
 * concurrency defect. Both concerns are tested here because they live in the same method.
 */
class ExperienceViewFunctionalIT extends FunctionalTestBase {

    private static final String CONFIDENTIAL = "Recruiter said the team is being reorganised.";
    private static final String PREP_ADVICE = "Read the paper on consistent hashing first.";

    private UUID publishedWithSecrets(Actor contributor, Actor admin) {
        return publishedExperience(contributor, admin, Payloads.experience()
                .put("confidentialNote", CONFIDENTIAL)
                .put("prepAdvice", PREP_ADVICE));
    }

    // --- Who sees what -----------------------------------------------------------------------

    @Test
    @DisplayName("FT-VIEW-01: a guest gets the teaser, and 'full' is absent from the JSON entirely")
    void guestGetsTeaserOnly() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedWithSecrets(contributor, admin);

        ResponseEntity<String> response = getAnonymously("/api/v1/experiences/" + experienceId);

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject view = jsonOf(response);
        assertThat(view.getBoolean("entitled")).isFalse();
        assertThat(view.has("teaser")).isTrue();
        // Absent, not null: the union in shared/types.ts is discriminated, and a client that
        // sees the key at all may reasonably assume there's content behind it.
        assertThat(view.has("full")).isFalse();
        assertThat(response.getBody()).doesNotContain(CONFIDENTIAL).doesNotContain(PREP_ADVICE);
    }

    @Test
    @DisplayName("FT-VIEW-02: a signed-in non-purchaser also gets only the teaser")
    void signedInNonPurchaserGetsTeaserOnly() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor stranger = registerUser();
        UUID experienceId = publishedWithSecrets(contributor, admin);

        JSONObject teaser = teaserOf(stranger, experienceId);

        assertThat(teaser.getString("company")).isEqualTo("Acme Corp");
        assertThat(teaser.getBoolean("unlocked")).isFalse();
        assertThat(teaser.has("prepAdvice")).isFalse();
    }

    @Test
    @DisplayName("FT-VIEW-03: the owner sees everything, including their own confidential note")
    void ownerSeesEverything() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedWithSecrets(contributor, admin);

        JSONObject full = fullOf(contributor, experienceId);

        assertThat(full.getJSONArray("rounds").length()).isEqualTo(1);
        assertThat(full.getString("prepAdvice")).isEqualTo(PREP_ADVICE);
        assertThat(full.getString("confidentialNote")).isEqualTo(CONFIDENTIAL);
    }

    @Test
    @DisplayName("FT-VIEW-04: an admin sees everything, because they have to review it")
    void adminSeesEverything() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedWithSecrets(contributor, admin);

        JSONObject full = fullOf(admin, experienceId);

        assertThat(full.getJSONArray("rounds").length()).isEqualTo(1);
        assertThat(full.getString("confidentialNote")).isEqualTo(CONFIDENTIAL);
    }

    @Test
    @DisplayName("FT-VIEW-05: a purchaser gets the content but NOT the confidential note")
    void purchaserGetsContentButNotTheConfidentialNote() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedWithSecrets(contributor, admin);
        purchase(buyer, experienceId);

        ResponseEntity<String> response = get("/api/v1/experiences/" + experienceId, buyer);
        JSONObject full = jsonOf(response).getJSONObject("full");

        assertThat(jsonOf(response).getBoolean("entitled")).isTrue();
        assertThat(full.getJSONArray("rounds").length()).isEqualTo(1);
        assertThat(full.getString("prepAdvice")).isEqualTo(PREP_ADVICE);
        // The contributor wrote this note for the platform, not for buyers. Given the NDA
        // exposure this product already carries, leaking it would be the worst kind of bug:
        // silent, and a betrayal of the person who trusted the platform with it.
        assertThat(full.isNull("confidentialNote")).isTrue();
        assertThat(response.getBody()).doesNotContain(CONFIDENTIAL);
    }

    @Test
    @DisplayName("FT-VIEW-06: a free published experience is fully open, minus the confidential note")
    void freePublishedExperienceIsOpenToEveryone() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor,
                Payloads.freeContribution().put("confidentialNote", CONFIDENTIAL));
        addRound(contributor, experienceId);
        post("/api/v1/experiences/" + experienceId + "/submit", contributor, null);

        ResponseEntity<String> asGuest = getAnonymously("/api/v1/experiences/" + experienceId);

        assertThat(statusOf(asGuest)).isEqualTo(200);
        JSONObject view = jsonOf(asGuest);
        assertThat(view.getBoolean("entitled")).isTrue();
        assertThat(view.getJSONObject("full").getJSONArray("rounds").length()).isEqualTo(1);
        assertThat(view.getJSONObject("full").isNull("confidentialNote")).isTrue();
        assertThat(asGuest.getBody()).doesNotContain(CONFIDENTIAL);
    }

    @Test
    @DisplayName("FT-VIEW-07: an unpublished experience is a 404 to anyone who isn't entitled")
    void unpublishedIsInvisibleToStrangers() {
        Actor contributor = registerUser();
        Actor stranger = registerUser();
        UUID draft = createDraft(contributor);
        UUID pending = submittedExperience(contributor);

        for (UUID id : List.of(draft, pending)) {
            assertThat(statusOf(get("/api/v1/experiences/" + id, stranger))).isEqualTo(404);
            assertThat(statusOf(getAnonymously("/api/v1/experiences/" + id))).isEqualTo(404);
        }
        // The owner can still open their own.
        assertThat(statusOf(get("/api/v1/experiences/" + draft, contributor))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-VIEW-08/09: unknown and malformed ids fail cleanly")
    void unknownAndMalformedIdsFailCleanly() {
        Actor viewer = registerUser();

        ResponseEntity<String> unknown = get("/api/v1/experiences/" + UUID.randomUUID(), viewer);
        ResponseEntity<String> malformed = get("/api/v1/experiences/not-a-uuid", viewer);

        assertThat(statusOf(unknown)).isEqualTo(404);
        assertThat(statusOf(malformed)).isEqualTo(400);
        assertThat(messageOf(malformed)).contains("Invalid value for 'id'");
    }

    // --- View counting -------------------------------------------------------------------------

    @Test
    @DisplayName("FT-VIEW-10/14: a view counts once per signed-in viewer, and shows up immediately")
    void viewsAreCountedOncePerViewer() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor firstViewer = registerUser();
        Actor secondViewer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        long baseline = teaserOf(firstViewer, experienceId).getLong("viewCount");

        // FT-VIEW-14: the viewer's own view is reflected in the very response they get back —
        // this is what the re-read after the atomic increment is for.
        assertThat(baseline).isEqualTo(1);

        teaserOf(firstViewer, experienceId);
        teaserOf(firstViewer, experienceId);
        assertThat(teaserOf(firstViewer, experienceId).getLong("viewCount"))
                .as("the same person reloading is not a new view")
                .isEqualTo(1);

        teaserOf(secondViewer, experienceId);
        assertThat(teaserOf(secondViewer, experienceId).getLong("viewCount")).isEqualTo(2);
        assertThat(countRows("experience_views", "experience_id = ?", experienceId)).isEqualTo(2);
    }

    @Test
    @DisplayName("FT-VIEW-11: a guest's view is never counted")
    void guestViewsAreNotCounted() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);

        getAnonymously("/api/v1/experiences/" + experienceId);
        getAnonymously("/api/v1/experiences/" + experienceId);

        // There's no identity to de-duplicate a guest against, so counting them would make the
        // number meaningless (and trivially inflatable).
        JSONObject view = jsonOf(getAnonymously("/api/v1/experiences/" + experienceId));
        assertThat(view.getJSONObject("teaser").getLong("viewCount")).isZero();
        assertThat(countRows("experience_views")).isZero();
    }

    @Test
    @DisplayName("FT-VIEW-12/13: owner views count, but only while the experience is published")
    void ownerViewsCountOnlyWhilePublished() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID draft = createDraft(contributor);
        addRound(contributor, draft);

        // FT-VIEW-13: nothing is recorded for an unpublished experience.
        fullOf(contributor, draft);
        fullOf(contributor, draft);
        assertThat(countRows("experience_views")).isZero();
        assertThat(fullOf(contributor, draft).getLong("viewCount")).isZero();

        // FT-VIEW-12: once published, the owner's own visit counts like anyone else's.
        UUID published = publishedExperience(contributor, admin,
                Payloads.experience("Published", "Engineer", "Live."));
        assertThat(fullOf(contributor, published).getLong("viewCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-VIEW-15: concurrent first views by different people don't collide (§1.2)")
    void concurrentFirstViewsDoNotCollide() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);

        int viewers = 8;
        List<Actor> audience = new ArrayList<>(viewers);
        for (int i = 0; i < viewers; i++) {
            audience.add(registerUser());
        }

        // All eight land on the detail page at once. Before the fix, getPublicView bumped
        // view_count through the @Version-guarded entity, so all but one of these got a
        // 409 "updated by someone else" on what is, to them, a plain page load.
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(viewers);
        try {
            List<Callable<Integer>> calls = new ArrayList<>(viewers);
            for (Actor viewer : audience) {
                calls.add(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return statusOf(get("/api/v1/experiences/" + experienceId, viewer));
                });
            }
            List<Future<Integer>> futures = new ArrayList<>(viewers);
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(call));
            }
            startTogether.countDown();

            for (Future<Integer> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS))
                        .as("a concurrent page load must not fail with a lock conflict")
                        .isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        // And no increment was lost: the atomic UPDATE serialises in the database.
        assertThat(countRows("experience_views", "experience_id = ?", experienceId)).isEqualTo(viewers);
        Long counted = jdbc.queryForObject(
                "SELECT view_count FROM experiences WHERE id = ?", Long.class, experienceId);
        assertThat(counted).isEqualTo(viewers);
    }

    @Test
    @DisplayName("FT-VIEW-16: a view never bumps the optimistic-lock version of the experience")
    void viewingDoesNotInvalidateAConcurrentEdit() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor viewer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        Long versionBefore = jdbc.queryForObject(
                "SELECT version FROM experiences WHERE id = ?", Long.class, experienceId);

        teaserOf(viewer, experienceId);

        Long versionAfter = jdbc.queryForObject(
                "SELECT version FROM experiences WHERE id = ?", Long.class, experienceId);
        // Deliberate: a view is not a content edit, and letting it invalidate an editor's
        // in-flight update would cost more than lost-update protection buys on a counter.
        assertThat(versionAfter).isEqualTo(versionBefore);
    }
}
