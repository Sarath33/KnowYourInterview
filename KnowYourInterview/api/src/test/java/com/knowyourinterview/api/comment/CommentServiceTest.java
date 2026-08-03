package com.knowyourinterview.api.comment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.knowyourinterview.api.auth.EmailNotVerifiedException;
import com.knowyourinterview.api.auth.EmailVerificationGuard;
import com.knowyourinterview.api.comment.dto.CommentResponse;
import com.knowyourinterview.api.comment.dto.CreateCommentRequest;
import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceOutcome;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.payment.EntitlementRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for CommentService — repositories and the email-verification guard are
 * mocked. Covers the access predicate (free vs paid, guest/owner/admin/entitled), post
 * validation, one-level-reply enforcement, soft-delete authorization, and the tree
 * shape/ordering + canDelete/authorIsContributor flags. Mirrors AdminReviewServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private ExperienceRepository experienceRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailVerificationGuard emailVerificationGuard;

    private CommentService service() {
        return new CommentService(
                experienceRepository, entitlementRepository, commentRepository, userRepository,
                emailVerificationGuard);
    }

    private static final UUID EXPERIENCE_ID = UUID.randomUUID();
    private static final UUID CONTRIBUTOR_ID = UUID.randomUUID();

    private Experience experience(boolean free) {
        Experience e = new Experience(
                EXPERIENCE_ID, CONTRIBUTOR_ID, "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", null, free ? 0 : 9900);
        if (free) {
            e.markAsFreeContribution();
        }
        e.publish();
        return e;
    }

    private void experienceExists(Experience e) {
        when(experienceRepository.findById(EXPERIENCE_ID)).thenReturn(Optional.of(e));
    }

    private User user(UUID id, String name) {
        return new User(id, "u-" + id + "@example.test", "hash", name);
    }

    private CreateCommentRequest body(String text) {
        return new CreateCommentRequest(text, null);
    }

    // --- Read access ------------------------------------------------------------------------

    @Test
    void guestCanReadCommentsOnAFreePublishedExperience() {
        experienceExists(experience(true));
        when(commentRepository.findByExperienceId(EXPERIENCE_ID)).thenReturn(List.of());

        List<CommentResponse> result = service().list(null, false, EXPERIENCE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void listOnUnknownExperienceIs404() {
        when(experienceRepository.findById(EXPERIENCE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().list(null, false, EXPERIENCE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listOnAnUnpublishedExperienceIs404() {
        Experience draft = new Experience(
                EXPERIENCE_ID, CONTRIBUTOR_ID, "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", null, 9900);
        experienceExists(draft);

        assertThatThrownBy(() -> service().list(null, false, EXPERIENCE_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void nonEntitledViewerIsForbiddenFromReadingAPaidExperiencesComments() {
        experienceExists(experience(false));
        UUID stranger = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(stranger, EXPERIENCE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service().list(stranger, false, EXPERIENCE_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void guestIsForbiddenFromReadingAPaidExperiencesComments() {
        experienceExists(experience(false));

        assertThatThrownBy(() -> service().list(null, false, EXPERIENCE_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    // --- Post access ------------------------------------------------------------------------

    @Test
    void nonEntitledViewerIsForbiddenFromPostingOnAPaidExperience() {
        experienceExists(experience(false));
        UUID stranger = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(stranger, EXPERIENCE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service().create(stranger, false, EXPERIENCE_ID, body("hi")))
                .isInstanceOf(ForbiddenException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void entitledViewerCanPostOnAPaidExperience() {
        experienceExists(experience(false));
        UUID buyer = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(buyer, EXPERIENCE_ID)).thenReturn(true);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(buyer, "Buyer")));

        CommentResponse created = service().create(buyer, false, EXPERIENCE_ID, body("great writeup"));

        assertThat(created.body()).isEqualTo("great writeup");
        assertThat(created.authorId()).isEqualTo(buyer);
        assertThat(created.parentId()).isNull();
        assertThat(created.replies()).isEmpty();
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    void ownerCanPostAndTheNodeIsFlaggedAuthorIsContributor() {
        experienceExists(experience(false));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(CONTRIBUTOR_ID, "Owner")));

        CommentResponse created = service().create(CONTRIBUTOR_ID, false, EXPERIENCE_ID, body("thanks all"));

        assertThat(created.authorIsContributor()).isTrue();
        assertThat(created.canDelete()).isTrue();
    }

    @Test
    void adminCanPostEvenWithoutAnEntitlement() {
        experienceExists(experience(false));
        UUID admin = UUID.randomUUID();
        when(userRepository.findAllById(any())).thenReturn(List.of(user(admin, "Admin")));

        CommentResponse created = service().create(admin, true, EXPERIENCE_ID, body("moderating"));

        assertThat(created.authorId()).isEqualTo(admin);
        // No entitlement check should be needed once admin short-circuits the predicate.
        verify(entitlementRepository, never()).existsByUserIdAndExperienceId(any(), any());
    }

    @Test
    void unverifiedEmailBlocksPosting() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        doThrow(new EmailNotVerifiedException("confirm first"))
                .when(emailVerificationGuard).requireVerified(any(), anyString());

        assertThatThrownBy(() -> service().create(author, false, EXPERIENCE_ID, body("hi")))
                .isInstanceOf(EmailNotVerifiedException.class);
        verify(commentRepository, never()).save(any());
    }

    // --- Post validation --------------------------------------------------------------------

    @Test
    void blankBodyIsRejected() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();

        assertThatThrownBy(() -> service().create(author, false, EXPERIENCE_ID, body("   ")))
                .isInstanceOf(InvalidStateException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void overLongBodyIsRejected() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        String tooLong = "x".repeat(4001);

        assertThatThrownBy(() -> service().create(author, false, EXPERIENCE_ID, body(tooLong)))
                .isInstanceOf(InvalidStateException.class);
        verify(commentRepository, never()).save(any());
    }

    // --- One-level-reply enforcement --------------------------------------------------------

    @Test
    void replyToATopLevelCommentIsAccepted() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Comment parent = new Comment(parentId, EXPERIENCE_ID, UUID.randomUUID(), null, "top");
        when(commentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(author, "Replier")));

        CommentResponse created = service().create(
                author, false, EXPERIENCE_ID, new CreateCommentRequest("a reply", parentId.toString()));

        assertThat(created.parentId()).isEqualTo(parentId);
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    void replyToAReplyIsRejected() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        // The "parent" is itself a reply (its own parentId is non-null).
        Comment aReply = new Comment(replyId, EXPERIENCE_ID, UUID.randomUUID(), UUID.randomUUID(), "reply");
        when(commentRepository.findById(replyId)).thenReturn(Optional.of(aReply));

        assertThatThrownBy(() -> service().create(
                author, false, EXPERIENCE_ID, new CreateCommentRequest("nested", replyId.toString())))
                .isInstanceOf(InvalidStateException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void replyToAParentFromAnotherExperienceIsRejected() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Comment foreignParent = new Comment(parentId, UUID.randomUUID(), UUID.randomUUID(), null, "elsewhere");
        when(commentRepository.findById(parentId)).thenReturn(Optional.of(foreignParent));

        assertThatThrownBy(() -> service().create(
                author, false, EXPERIENCE_ID, new CreateCommentRequest("x", parentId.toString())))
                .isInstanceOf(NotFoundException.class);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void replyToADeletedParentIsRejected() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        Comment deletedParent = new Comment(parentId, EXPERIENCE_ID, UUID.randomUUID(), null, "gone");
        deletedParent.markDeleted();
        when(commentRepository.findById(parentId)).thenReturn(Optional.of(deletedParent));

        assertThatThrownBy(() -> service().create(
                author, false, EXPERIENCE_ID, new CreateCommentRequest("x", parentId.toString())))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void unknownParentIsRejected() {
        experienceExists(experience(true));
        UUID author = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(commentRepository.findById(parentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(
                author, false, EXPERIENCE_ID, new CreateCommentRequest("x", parentId.toString())))
                .isInstanceOf(NotFoundException.class);
    }

    // --- Soft delete ------------------------------------------------------------------------

    @Test
    void authorCanSoftDeleteTheirOwnComment() {
        UUID author = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        Comment comment = new Comment(commentId, EXPERIENCE_ID, author, null, "mine");
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        service().delete(author, false, EXPERIENCE_ID, commentId);

        assertThat(comment.isDeleted()).isTrue();
        verify(commentRepository).save(comment);
    }

    @Test
    void adminCanSoftDeleteAnotherUsersComment() {
        UUID author = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        Comment comment = new Comment(commentId, EXPERIENCE_ID, author, null, "theirs");
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        service().delete(admin, true, EXPERIENCE_ID, commentId);

        assertThat(comment.isDeleted()).isTrue();
    }

    @Test
    void strangerCannotDeleteAnotherUsersComment() {
        UUID author = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        Comment comment = new Comment(commentId, EXPERIENCE_ID, author, null, "not yours");
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> service().delete(stranger, false, EXPERIENCE_ID, commentId))
                .isInstanceOf(ForbiddenException.class);
        assertThat(comment.isDeleted()).isFalse();
        verify(commentRepository, never()).save(any());
    }

    @Test
    void deletingACommentFromAnotherExperienceIs404() {
        UUID commentId = UUID.randomUUID();
        Comment comment = new Comment(commentId, UUID.randomUUID(), UUID.randomUUID(), null, "elsewhere");
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> service().delete(UUID.randomUUID(), true, EXPERIENCE_ID, commentId))
                .isInstanceOf(NotFoundException.class);
    }

    // --- Tree shape, ordering, and rendering ------------------------------------------------

    @Test
    void treeOrdersTopLevelNewestFirstAndRepliesOldestFirstWithFlags() {
        experienceExists(experience(true));

        UUID viewer = UUID.randomUUID();
        UUID otherAuthor = UUID.randomUUID();

        // Two top-level comments; older then newer.
        Comment olderTop = commentAt(UUID.randomUUID(), null, viewer, "older top", secondsAgo(100));
        Comment newerTop = commentAt(UUID.randomUUID(), null, CONTRIBUTOR_ID, "newer top", secondsAgo(10));
        // Two replies under the older top; first then second.
        Comment firstReply = commentAt(UUID.randomUUID(), olderTop.getId(), otherAuthor, "first reply", secondsAgo(80));
        Comment secondReply = commentAt(UUID.randomUUID(), olderTop.getId(), viewer, "second reply", secondsAgo(60));

        when(commentRepository.findByExperienceId(EXPERIENCE_ID))
                .thenReturn(List.of(olderTop, firstReply, newerTop, secondReply));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(viewer, "Viewer"), user(otherAuthor, "Other"), user(CONTRIBUTOR_ID, "Owner")));

        List<CommentResponse> tree = service().list(viewer, false, EXPERIENCE_ID);

        // Top-level newest-first: newerTop before olderTop.
        assertThat(tree).extracting(CommentResponse::body).containsExactly("newer top", "older top");
        // newerTop is authored by the contributor.
        assertThat(tree.get(0).authorIsContributor()).isTrue();
        assertThat(tree.get(0).authorName()).isEqualTo("Owner");
        // olderTop is authored by the viewer -> canDelete true, not contributor.
        CommentResponse older = tree.get(1);
        assertThat(older.canDelete()).isTrue();
        assertThat(older.authorIsContributor()).isFalse();
        // Replies oldest-first.
        assertThat(older.replies()).extracting(CommentResponse::body)
                .containsExactly("first reply", "second reply");
        // A reply not authored by the viewer isn't deletable by them.
        assertThat(older.replies().get(0).canDelete()).isFalse();
        assertThat(older.replies().get(1).canDelete()).isTrue();
    }

    @Test
    void deletedCommentIsRedactedButKeepsItsReplies() {
        experienceExists(experience(true));

        UUID viewer = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        Comment deletedTop = commentAt(UUID.randomUUID(), null, author, "was here", secondsAgo(100));
        deletedTop.markDeleted();
        Comment reply = commentAt(UUID.randomUUID(), deletedTop.getId(), viewer, "still here", secondsAgo(50));

        when(commentRepository.findByExperienceId(EXPERIENCE_ID)).thenReturn(List.of(deletedTop, reply));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(viewer, "Viewer")));

        List<CommentResponse> tree = service().list(viewer, false, EXPERIENCE_ID);

        assertThat(tree).hasSize(1);
        CommentResponse top = tree.get(0);
        assertThat(top.deleted()).isTrue();
        assertThat(top.body()).isEqualTo("[deleted]");
        assertThat(top.authorId()).isNull();
        assertThat(top.authorName()).isNull();
        assertThat(top.canDelete()).isFalse();
        assertThat(top.authorIsContributor()).isFalse();
        // id/parentId/createdAt preserved, and the reply survives.
        assertThat(top.id()).isEqualTo(deletedTop.getId());
        assertThat(top.createdAt()).isEqualTo(deletedTop.getCreatedAt());
        assertThat(top.replies()).extracting(CommentResponse::body).containsExactly("still here");
    }

    // --- helpers ----------------------------------------------------------------------------

    private static Instant secondsAgo(long seconds) {
        return Instant.now().minusSeconds(seconds);
    }

    /** A Comment with a controlled createdAt — the constructor stamps now(), whose resolution
     * isn't enough to order deterministically within a test, so we set the field directly. */
    private static Comment commentAt(UUID id, UUID parentId, UUID authorId, String body, Instant createdAt) {
        Comment comment = new Comment(id, EXPERIENCE_ID, authorId, parentId, body);
        ReflectionTestUtils.setField(comment, "createdAt", createdAt);
        return comment;
    }
}
