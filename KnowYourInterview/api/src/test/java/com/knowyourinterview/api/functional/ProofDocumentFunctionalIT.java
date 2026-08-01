package com.knowyourinterview.api.functional;

import java.util.List;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-PROOF — proof documents: upload, download, and above all who is allowed to see them. See
 * {@code docs/09-test-plan.md} §7.6.
 *
 * <p>These files are offer letters and interview invites: real names, real employers, sometimes
 * compensation. They are the most sensitive data the application holds, and the handoff already
 * flags their handling as an open item. Access control on them is a P0 throughout.
 */
class ProofDocumentFunctionalIT extends FunctionalTestBase {

    private static final byte[] PDF_BYTES = "%PDF-1.4 pretend offer letter contents".getBytes();

    @Test
    @DisplayName("FT-PROOF-01: upload and download round-trips the exact bytes")
    void uploadAndDownloadRoundTrip() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        ResponseEntity<String> upload = postFile("/api/v1/experiences/" + experienceId + "/proof",
                contributor, "offer-letter.pdf", "application/pdf", PDF_BYTES);

        assertThat(statusOf(upload)).isEqualTo(201);
        JSONObject document = jsonOf(upload);
        assertThat(document.getString("fileName")).isEqualTo("offer-letter.pdf");
        assertThat(document.getString("contentType")).isEqualTo("application/pdf");
        UUID proofId = UUID.fromString(document.getString("id"));

        ResponseEntity<String> download =
                get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, contributor);

        assertThat(statusOf(download)).isEqualTo(200);
        assertThat(download.getBody()).isEqualTo(new String(PDF_BYTES));
        assertThat(download.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("offer-letter.pdf");
        assertThat(download.getHeaders().getContentType()).hasToString("application/pdf");
    }

    @Test
    @DisplayName("FT-PROOF-02: content types outside the allow-list are refused")
    void disallowedContentTypesAreRefused() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        record Upload(String fileName, String contentType) {
        }
        List<Upload> rejected = List.of(
                new Upload("payload.exe", "application/x-msdownload"),
                new Upload("archive.zip", "application/zip"),
                new Upload("page.html", "text/html"),
                new Upload("notes.txt", "text/plain"),
                new Upload("script.js", "application/javascript"),
                new Upload("anything.bin", "application/octet-stream"));

        for (Upload upload : rejected) {
            ResponseEntity<String> response = postFile("/api/v1/experiences/" + experienceId + "/proof",
                    contributor, upload.fileName(), upload.contentType(), "content".getBytes());

            assertThat(statusOf(response))
                    .as("%s (%s) should be refused", upload.fileName(), upload.contentType())
                    .isEqualTo(400);
            assertThat(messageOf(response)).contains("Unsupported file type");
        }
        // Nothing landed in the database or on disk.
        assertThat(countRows("proof_documents")).isZero();
    }

    @Test
    @DisplayName("FT-PROOF-03: PDFs and the allowed image types are accepted")
    void allowedTypesAreAccepted() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        List<String> allowed = List.of("application/pdf", "image/png", "image/jpeg", "image/webp");
        for (String contentType : allowed) {
            ResponseEntity<String> response = postFile("/api/v1/experiences/" + experienceId + "/proof",
                    contributor, "proof." + contentType.substring(contentType.indexOf('/') + 1),
                    contentType, "bytes".getBytes());

            assertThat(statusOf(response)).as("%s should be accepted", contentType).isEqualTo(201);
        }
        assertThat(countRows("proof_documents")).isEqualTo(allowed.size());
    }

    @Test
    @DisplayName("FT-PROOF-04: an empty file is refused")
    void emptyFileIsRefused() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        ResponseEntity<String> response = postFile("/api/v1/experiences/" + experienceId + "/proof",
                contributor, "empty.pdf", "application/pdf", new byte[0]);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("empty");
        assertThat(countRows("proof_documents")).isZero();
    }

    @Test
    @DisplayName("FT-PROOF-05: a missing file part is a 400 naming the part")
    void missingFilePartIsRejected() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        HttpHeaders headers = headers(contributor.accessToken());
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.MultiValueMap<String, Object> emptyParts =
                new org.springframework.util.LinkedMultiValueMap<>();
        emptyParts.add("notTheFile", "irrelevant");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/experiences/" + experienceId + "/proof", HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(emptyParts, headers), String.class);

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(messageOf(response)).contains("file");
    }

    @Test
    @DisplayName("FT-PROOF-06: a stranger cannot download someone else's proof")
    void strangerCannotDownloadProof() {
        Actor contributor = registerUser();
        Actor stranger = registerUser();
        UUID experienceId = createDraft(contributor);
        UUID proofId = uploadProof(contributor, experienceId);

        ResponseEntity<String> response =
                get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, stranger);

        assertThat(statusOf(response)).isEqualTo(403);
        assertThat(response.getBody()).doesNotContain("pretend offer letter");
    }

    @Test
    @DisplayName("FT-PROOF-07: an anonymous caller cannot download proof")
    void anonymousCannotDownloadProof() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);
        UUID proofId = uploadProof(contributor, experienceId);

        assertThat(statusOf(getAnonymously("/api/v1/experiences/" + experienceId + "/proof/" + proofId)))
                .isEqualTo(401);
    }

    @Test
    @DisplayName("FT-PROOF-08: buying the write-up does NOT buy access to the proof documents")
    void purchaserCannotDownloadProof() throws Exception {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor buyer = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        purchase(buyer, experienceId);

        JSONObject full = fullOf(buyer, experienceId);
        UUID proofId = UUID.fromString(full.getJSONArray("proofDocuments").getJSONObject(0).getString("id"));

        ResponseEntity<String> response = get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, buyer);

        // The buyer paid for the interview write-up, not for the contributor's identity
        // documents. The proof id is visible in their payload, so the endpoint itself is the
        // only thing enforcing this.
        assertThat(statusOf(response)).isEqualTo(403);
    }

    @Test
    @DisplayName("FT-PROOF-09: an admin can download proof, which is the point of proof")
    void adminCanDownloadProof() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(contributor);
        UUID proofId = uploadProof(contributor, experienceId);

        ResponseEntity<String> response = get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, admin);

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(response.getBody()).contains("functional test proof");
    }

    @Test
    @DisplayName("FT-PROOF-11: deleting a proof removes both the row and the file")
    void deletingProofRemovesRowAndFile() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);
        UUID proofId = uploadProof(contributor, experienceId);
        assertThat(proofDirectory().resolve(experienceId.toString()).toFile().listFiles()).hasSize(1);

        assertThat(statusOf(delete("/api/v1/experiences/" + experienceId + "/proof/" + proofId, contributor)))
                .isEqualTo(204);

        assertThat(countRows("proof_documents")).isZero();
        java.io.File[] remaining = proofDirectory().resolve(experienceId.toString()).toFile().listFiles();
        assertThat(remaining == null || remaining.length == 0)
                .as("the stored file must not outlive its row")
                .isTrue();
        assertThat(statusOf(get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, contributor)))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("FT-PROOF-12: a proof id can't be reached through another experience's path")
    void proofIsScopedToItsOwnExperience() {
        Actor contributor = registerUser();
        UUID first = createDraft(contributor);
        UUID second = createDraft(contributor);
        UUID proofOnFirst = uploadProof(contributor, first);

        assertThat(statusOf(get("/api/v1/experiences/" + second + "/proof/" + proofOnFirst, contributor)))
                .isEqualTo(404);
        assertThat(statusOf(delete("/api/v1/experiences/" + second + "/proof/" + proofOnFirst, contributor)))
                .isEqualTo(404);
        assertThat(countRows("proof_documents")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-PROOF-13: a path-traversal filename can't escape the storage root")
    void traversalFilenameIsNeutralised() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);

        ResponseEntity<String> response = postFile("/api/v1/experiences/" + experienceId + "/proof",
                contributor, "../../../../etc/passwd.pdf", "application/pdf", PDF_BYTES);

        // LocalProofStorageService sanitises the name before it becomes a path segment, so the
        // upload succeeds but lands harmlessly inside the experience's own directory.
        assertThat(statusOf(response)).isEqualTo(201);
        java.io.File[] stored = proofDirectory().resolve(experienceId.toString()).toFile().listFiles();
        assertThat(stored).hasSize(1);
        assertThat(stored[0].getName())
                .as("the stored filename must carry no path separators or traversal segments")
                .doesNotContain("/").doesNotContain("\\").doesNotContain("..");

        // And it's still readable through the normal route, so sanitising didn't break retrieval.
        UUID proofId = UUID.fromString(jsonOf(response).getString("id"));
        assertThat(statusOf(get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, contributor)))
                .isEqualTo(200);
    }
}
