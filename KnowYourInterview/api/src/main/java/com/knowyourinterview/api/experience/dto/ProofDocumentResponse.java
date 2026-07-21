package com.knowyourinterview.api.experience.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.experience.ProofDocument;

public record ProofDocumentResponse(UUID id, String fileName, String contentType, Instant uploadedAt) {

    public static ProofDocumentResponse from(ProofDocument p) {
        return new ProofDocumentResponse(p.getId(), p.getFileName(), p.getContentType(), p.getUploadedAt());
    }
}
