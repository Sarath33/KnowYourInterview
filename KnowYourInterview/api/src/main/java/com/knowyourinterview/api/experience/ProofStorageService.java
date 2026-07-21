package com.knowyourinterview.api.experience;

import java.io.InputStream;
import java.util.UUID;

/**
 * Storage for proof documents (offer letters, interview invites, etc.). These are
 * sensitive PII — never expose storage keys or contents except to the owning
 * contributor and admins. The local-disk implementation is dev-only; swap for an
 * S3-backed implementation in Phase 6 without touching callers.
 */
public interface ProofStorageService {

    record StoredFile(String storageKey, long sizeBytes) {}

    StoredFile store(UUID experienceId, String originalFileName, InputStream content);

    InputStream retrieve(String storageKey);
}
