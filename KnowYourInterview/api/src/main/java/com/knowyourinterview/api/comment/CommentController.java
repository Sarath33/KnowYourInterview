package com.knowyourinterview.api.comment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.comment.dto.CommentResponse;
import com.knowyourinterview.api.comment.dto.CreateCommentRequest;
import com.knowyourinterview.api.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Comments on an interview-experience page. See {@link CommentService} for the access rules;
 * SecurityConfig permits guests to reach only the GET (so a free experience is readable
 * without signing in), while POST/DELETE require authentication.
 */
@RestController
@RequestMapping("/api/v1/experiences/{experienceId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /** Nullable principal: a guest (null) may read a free experience's comments; the service
     * enforces the real access rule and 403s a guest on a paid experience. */
    @GetMapping
    public List<CommentResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID experienceId) {
        UUID viewerId = user == null ? null : user.id();
        boolean isAdmin = user != null && user.admin();
        return commentService.list(viewerId, isAdmin, experienceId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID experienceId,
            @Valid @RequestBody CreateCommentRequest req) {
        return commentService.create(user.id(), user.admin(), experienceId, req);
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID experienceId,
            @PathVariable UUID commentId) {
        commentService.delete(user.id(), user.admin(), experienceId, commentId);
    }
}
