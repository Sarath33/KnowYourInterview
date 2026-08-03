package com.knowyourinterview.api.comment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /** Every comment (top-level and replies, deleted or not) for one experience. The service
     * builds the newest-first / oldest-first tree and applies the soft-delete rendering in
     * memory — one query per page rather than a query per node. */
    List<Comment> findByExperienceId(UUID experienceId);
}
