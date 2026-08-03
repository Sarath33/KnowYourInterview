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
 * FT-COMMENTS — the interview-experience comment thread: who can read, who can post, one-level
 * replies, and soft-delete. Exercises the real endpoints through the full Spring Security chain
 * (so the guest-readable {@code GET /comments} permitAll rule and the service-level access gate
 * are both covered), mirroring {@link ExperienceViewFunctionalIT}. Run with {@code mvn verify}
 * (needs Docker).
 *
 * <p>Access mirrors the paywall exactly: a free+published experience's thread is open to
 * everyone including guests; a paid one only to the owner, an admin, or a purchaser.
 */
class CommentsFunctionalIT extends FunctionalTestBase {

    private String commentsPath(UUID experienceId) {
        return "/api/v1/experiences/" + experienceId + "/comments";
    }

    /** A free contribution publishes immediately on submit — open to everyone. */
    private UUID freeExperience(Actor contributor) {
        UUID id = createDraft(contributor, Payloads.freeContribution());
        addRound(contributor, id);
        ResponseEntity<String> submit = post("/api/v1/experiences/" + id + "/submit", contributor, null);
        assertThat(statusOf(submit)).as("free submit: %s", submit.getBody()).isEqualTo(200);
        return id;
    }

    private JSONObject postComment(Actor actor, UUID experienceId, String body, String parentId) {
        JSONObject payload = new JSONObject().put("body", body);
        payload.put("parentId", parentId == null ? JSONObject.NULL : parentId);
        ResponseEntity<String> response = post(commentsPath(experienceId), actor, payload);
        assertThat(statusOf(response)).as("post comment: %s", response.getBody()).isEqualTo(201);
        return jsonOf(response);
    }

    // --- Read access ------------------------------------------------------------------------

    @Test
    @DisplayName("FT-COMMENTS-01: a guest can read a free experience's comments")
    void guestReadsFreeExperienceComments() {
        Actor contributor = registerUser();
        UUID experienceId = freeExperience(contributor);
        postComment(contributor, experienceId, "First!", null);

        ResponseEntity<String> response = getAnonymously(commentsPath(experienceId));

        assertThat(statusOf(response)).isEqualTo(200);
        JSONArray comments = jsonArrayOf(response);
        assertThat(comments.length()).isEqualTo(1);
        assertThat(comments.getJSONObject(0).getString("body")).isEqualTo("First!");
    }

    @Test
    @DisplayName("FT-COMMENTS-02: a guest is blocked from a paid experience's comments (403)")
    void guestBlockedFromPaidExperienceComments() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = publishedExperience(contributor, admin);

        assertThat(statusOf(getAnonymously(commentsPath(experienceId)))).isEqualTo(403);
    }

    @Test
    @DisplayName("FT-COMMENTS-03: a signed-in non-purchaser is blocked from a paid thread (403)")
    void nonPurchaserBlockedFromPaidThread() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor stranger = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);

        assertThat(statusOf(get(commentsPath(experienceId), stranger))).isEqualTo(403);
    }

    @Test
    @DisplayName("FT-COMMENTS-04: an unpublished experience's thread is a 404")
    void unpublishedThreadIs404() {
        Actor contributor = registerUser();
        UUID draft = createDraft(contributor);

        assertThat(statusOf(get(commentsPath(draft), contributor))).isEqualTo(404);
    }

    // --- Posting ----------------------------------------------------------------------------

    @Test
    @DisplayName("FT-COMMENTS-05: posting requires authentication")
    void postingRequiresAuth() {
        Actor contributor = registerUser();
        UUID experienceId = freeExperience(contributor);

        ResponseEntity<String> response = post(
                commentsPath(experienceId), null, new JSONObject().put("body", "anon"));
        assertThat(statusOf(response)).isEqualTo(401);
    }

    @Test
    @DisplayName("FT-COMMENTS-06: an entitled buyer posts a comment and a reply; the tree nests one level")
    void entitledBuyerPostsAndReplies() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        purchase(buyer, experienceId);

        JSONObject top = postComment(buyer, experienceId, "How long was the loop?", null);
        String topId = top.getString("id");
        assertThat(top.isNull("parentId")).isTrue();
        assertThat(top.getJSONArray("replies").length()).isZero();

        JSONObject reply = postComment(contributor, experienceId, "About three weeks.", topId);
        assertThat(reply.getString("parentId")).isEqualTo(topId);
        // The contributor's own reply is flagged.
        assertThat(reply.getBoolean("authorIsContributor")).isTrue();

        JSONArray tree = jsonArrayOf(get(commentsPath(experienceId), buyer));
        assertThat(tree.length()).isEqualTo(1);
        JSONObject node = tree.getJSONObject(0);
        assertThat(node.getString("id")).isEqualTo(topId);
        JSONArray replies = node.getJSONArray("replies");
        assertThat(replies.length()).isEqualTo(1);
        assertThat(replies.getJSONObject(0).getString("body")).isEqualTo("About three weeks.");
    }

    @Test
    @DisplayName("FT-COMMENTS-07: a reply to a reply is rejected (one level only)")
    void replyToAReplyIsRejected() {
        Actor contributor = registerUser();
        UUID experienceId = freeExperience(contributor);
        String topId = postComment(contributor, experienceId, "top", null).getString("id");
        String replyId = postComment(contributor, experienceId, "reply", topId).getString("id");

        ResponseEntity<String> response = post(commentsPath(experienceId), contributor,
                new JSONObject().put("body", "nested").put("parentId", replyId));
        assertThat(statusOf(response)).isEqualTo(400);
    }

    @Test
    @DisplayName("FT-COMMENTS-08: a blank body is a 400 with the field-error envelope")
    void blankBodyIsRejected() {
        Actor contributor = registerUser();
        UUID experienceId = freeExperience(contributor);

        ResponseEntity<String> response = post(commentsPath(experienceId), contributor,
                new JSONObject().put("body", "   "));
        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(jsonOf(response).getJSONObject("fieldErrors").has("body")).isTrue();
    }

    // --- Ordering ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-COMMENTS-09: top-level comments are newest-first, replies oldest-first")
    void ordering() {
        Actor contributor = registerUser();
        UUID experienceId = freeExperience(contributor);

        String firstTop = postComment(contributor, experienceId, "first top", null).getString("id");
        postComment(contributor, experienceId, "second top", null);
        postComment(contributor, experienceId, "reply A", firstTop);
        postComment(contributor, experienceId, "reply B", firstTop);

        JSONArray tree = jsonArrayOf(get(commentsPath(experienceId), contributor));
        assertThat(tree.length()).isEqualTo(2);
        // Newest top-level first.
        assertThat(tree.getJSONObject(0).getString("body")).isEqualTo("second top");
        assertThat(tree.getJSONObject(1).getString("body")).isEqualTo("first top");
        // Replies oldest-first under the first top.
        JSONArray replies = tree.getJSONObject(1).getJSONArray("replies");
        assertThat(replies.getJSONObject(0).getString("body")).isEqualTo("reply A");
        assertThat(replies.getJSONObject(1).getString("body")).isEqualTo("reply B");
    }

    // --- Soft delete ------------------------------------------------------------------------

    @Test
    @DisplayName("FT-COMMENTS-10: an author soft-deletes their own comment; it renders as [deleted] but its reply survives")
    void authorSoftDeletesOwnComment() {
        Actor contributor = registerUser();
        Actor other = registerUser();
        UUID experienceId = freeExperience(contributor);
        String topId = postComment(contributor, experienceId, "delete me", null).getString("id");
        postComment(other, experienceId, "a reply under it", topId);

        ResponseEntity<String> delete = delete(commentsPath(experienceId) + "/" + topId, contributor);
        assertThat(statusOf(delete)).isEqualTo(204);

        JSONArray tree = jsonArrayOf(get(commentsPath(experienceId), contributor));
        JSONObject node = tree.getJSONObject(0);
        assertThat(node.getBoolean("deleted")).isTrue();
        assertThat(node.getString("body")).isEqualTo("[deleted]");
        assertThat(node.isNull("authorId")).isTrue();
        assertThat(node.isNull("authorName")).isTrue();
        // The reply is preserved.
        assertThat(node.getJSONArray("replies").getJSONObject(0).getString("body"))
                .isEqualTo("a reply under it");
        // Row is soft-deleted, not removed.
        assertThat(countRows("experience_comments", "experience_id = ?", experienceId)).isEqualTo(2);
    }

    @Test
    @DisplayName("FT-COMMENTS-11: an admin can delete another user's comment")
    void adminDeletesAnothersComment() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = freeExperience(contributor);
        String commentId = postComment(contributor, experienceId, "contributor comment", null).getString("id");

        ResponseEntity<String> delete = delete(commentsPath(experienceId) + "/" + commentId, admin);
        assertThat(statusOf(delete)).isEqualTo(204);

        JSONObject node = jsonArrayOf(get(commentsPath(experienceId), contributor)).getJSONObject(0);
        assertThat(node.getBoolean("deleted")).isTrue();
    }

    @Test
    @DisplayName("FT-COMMENTS-12: a stranger cannot delete someone else's comment (403)")
    void strangerCannotDelete() {
        Actor contributor = registerUser();
        Actor stranger = registerUser();
        UUID experienceId = freeExperience(contributor);
        String commentId = postComment(contributor, experienceId, "mine", null).getString("id");

        ResponseEntity<String> delete = delete(commentsPath(experienceId) + "/" + commentId, stranger);
        assertThat(statusOf(delete)).isEqualTo(403);
    }
}
