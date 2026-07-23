package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "experience_rounds")
public class ExperienceRound {

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "round_number", nullable = false)
    private short roundNumber;

    @Column(name = "round_type", nullable = false)
    private String roundType;

    @Column(name = "duration_minutes")
    private Short durationMinutes;

    @Column(name = "questions_asked", columnDefinition = "text")
    private String questionsAsked;

    @Column(name = "topics_tags")
    private String topicsTags;

    @Column(columnDefinition = "text")
    private String approach;

    @Column(name = "interviewer_behavior", columnDefinition = "text")
    private String interviewerBehavior;

    private Short difficulty;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExperienceRound() {
        // JPA
    }

    public ExperienceRound(
            UUID id,
            UUID experienceId,
            short roundNumber,
            String roundType,
            Short durationMinutes,
            String questionsAsked,
            String topicsTags,
            String approach,
            String interviewerBehavior,
            Short difficulty) {
        this.id = id;
        this.experienceId = experienceId;
        this.roundNumber = roundNumber;
        this.roundType = roundType;
        this.durationMinutes = durationMinutes;
        this.questionsAsked = questionsAsked;
        this.topicsTags = topicsTags;
        this.approach = approach;
        this.interviewerBehavior = interviewerBehavior;
        this.difficulty = difficulty;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperienceId() {
        return experienceId;
    }

    public short getRoundNumber() {
        return roundNumber;
    }

    public String getRoundType() {
        return roundType;
    }

    public Short getDurationMinutes() {
        return durationMinutes;
    }

    public String getQuestionsAsked() {
        return questionsAsked;
    }

    public String getTopicsTags() {
        return topicsTags;
    }

    public String getApproach() {
        return approach;
    }

    public String getInterviewerBehavior() {
        return interviewerBehavior;
    }

    public Short getDifficulty() {
        return difficulty;
    }

    /** Edits every field except id/experienceId/roundNumber/createdAt — mirrors
     * Experience#applyEdits. roundNumber deliberately isn't touched here; reordering
     * rounds isn't something this supports (remove-and-re-add is still how you'd change
     * ordering, same as before this existed). */
    public void applyEdits(
            String roundType,
            Short durationMinutes,
            String questionsAsked,
            String topicsTags,
            String approach,
            String interviewerBehavior,
            Short difficulty) {
        this.roundType = roundType;
        this.durationMinutes = durationMinutes;
        this.questionsAsked = questionsAsked;
        this.topicsTags = topicsTags;
        this.approach = approach;
        this.interviewerBehavior = interviewerBehavior;
        this.difficulty = difficulty;
    }
}
