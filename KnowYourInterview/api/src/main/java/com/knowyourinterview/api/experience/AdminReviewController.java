package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.RejectRequest;
import com.knowyourinterview.api.security.AuthenticatedUser;

import jakarta.validation.Valid;

/** All routes here require ROLE_ADMIN — enforced in SecurityConfig via the /api/v1/admin/** pattern. */
@RestController
@RequestMapping("/api/v1/admin/experiences")
public class AdminReviewController {

    private final AdminReviewService adminReviewService;

    public AdminReviewController(AdminReviewService adminReviewService) {
        this.adminReviewService = adminReviewService;
    }

    @GetMapping
    public List<ExperienceFullResponse> reviewQueue() {
        return adminReviewService.reviewQueue();
    }

    @GetMapping("/{id}")
    public ExperienceFullResponse getForReview(@PathVariable UUID id) {
        return adminReviewService.getForReview(id);
    }

    @PostMapping("/{id}/approve")
    public ExperienceFullResponse approve(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
        return adminReviewService.approve(admin.id(), id);
    }

    @PostMapping("/{id}/reject")
    public ExperienceFullResponse reject(
            @AuthenticationPrincipal AuthenticatedUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody RejectRequest req) {
        return adminReviewService.reject(admin.id(), id, req.reason());
    }
}
