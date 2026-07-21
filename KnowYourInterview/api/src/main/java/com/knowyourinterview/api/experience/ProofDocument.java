package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "proof_documents")
public class ProofDocument {

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    /** Storage key: currently a relative path under the local proof-dir (see ProofStorageService). */
    @Column(name = "s3_key", nullable = false, length = 1024)
    private String storageKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected ProofDocument() {
        // JPA
    }

    public ProofDocument(UUID id, UUID experienceId, String storageKey, String fileName, String contentType) {
        this.id = id;
        this.experienceId = experienceId;
        this.storageKey = storageKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperienceId() {
        return experienceId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
