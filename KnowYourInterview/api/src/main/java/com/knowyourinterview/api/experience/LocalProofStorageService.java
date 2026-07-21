package com.knowyourinterview.api.experience;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LocalProofStorageService implements ProofStorageService {

    private final Path rootDir;

    public LocalProofStorageService(@Value("${app.storage.proof-dir}") String proofDir) {
        this.rootDir = Path.of(proofDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create proof storage directory: " + rootDir, e);
        }
    }

    @Override
    public StoredFile store(UUID experienceId, String originalFileName, InputStream content) {
        String safeName = sanitize(originalFileName);
        String storageKey = experienceId + "/" + UUID.randomUUID() + "-" + safeName;
        Path target = resolve(storageKey);

        try {
            Files.createDirectories(target.getParent());
            long size = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return new StoredFile(storageKey, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store proof document", e);
        }
    }

    @Override
    public InputStream retrieve(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read proof document: " + storageKey, e);
        }
    }

    private Path resolve(String storageKey) {
        Path resolved = rootDir.resolve(storageKey).normalize();
        if (!resolved.startsWith(rootDir)) {
            // Defends against a storageKey containing "../" escaping the storage root.
            throw new SecurityException("Invalid storage key");
        }
        return resolved;
    }

    private static String sanitize(String fileName) {
        String base = fileName == null ? "file" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return base.isBlank() ? "file" : base;
    }
}
