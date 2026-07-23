package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.knowyourinterview.api.common.PagedResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRequest;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ExperienceTeaserResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;
import com.knowyourinterview.api.experience.dto.RoundRequest;
import com.knowyourinterview.api.security.AuthenticatedUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/experiences")
public class ExperienceController {

    private final ExperienceService experienceService;

    public ExperienceController(ExperienceService experienceService) {
        this.experienceService = experienceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExperienceFullResponse createDraft(
            @AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ExperienceRequest req) {
        return experienceService.createDraft(user.id(), req);
    }

    @PutMapping("/{id}")
    public ExperienceFullResponse updateDraft(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ExperienceRequest req) {
        return experienceService.updateDraft(user.id(), id, req);
    }

    @PostMapping("/{id}/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    public ExperienceRoundResponse addRound(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody RoundRequest req) {
        return experienceService.addRound(user.id(), id, req);
    }

    @PutMapping("/{id}/rounds/{roundId}")
    public ExperienceRoundResponse updateRound(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @PathVariable UUID roundId,
            @Valid @RequestBody RoundRequest req) {
        return experienceService.updateRound(user.id(), id, roundId, req);
    }

    @DeleteMapping("/{id}/rounds/{roundId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRound(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @PathVariable UUID roundId) {
        experienceService.deleteRound(user.id(), id, roundId);
    }

    @PostMapping("/{id}/proof")
    @ResponseStatus(HttpStatus.CREATED)
    public ProofDocumentResponse uploadProof(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file) {
        return experienceService.uploadProof(user.id(), id, file);
    }

    @GetMapping("/{id}/proof/{proofId}")
    public ResponseEntity<InputStreamResource> downloadProof(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @PathVariable UUID proofId) {
        ExperienceService.ProofDownload download =
                experienceService.downloadProof(user.id(), user.admin(), id, proofId);
        // Spring's message converter reads and closes this stream while writing the response body.
        InputStreamResource resource = new InputStreamResource(download.content());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.document().getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.document().getFileName() + "\"")
                .body(resource);
    }

    @PostMapping("/{id}/submit")
    public ExperienceFullResponse submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return experienceService.submitForReview(user.id(), id);
    }

    @DeleteMapping("/{id}/proof/{proofId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProof(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id, @PathVariable UUID proofId) {
        experienceService.deleteProof(user.id(), id, proofId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExperience(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        experienceService.deleteExperience(user.id(), id);
    }

    /** Owner or admin — see ExperienceService#unpublish for why both can trigger this. */
    @PostMapping("/{id}/unpublish")
    public ExperienceFullResponse unpublish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return experienceService.unpublish(user.id(), user.admin(), id);
    }

    @GetMapping("/mine")
    public List<ExperienceFullResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return experienceService.listMine(user.id());
    }

    @GetMapping
    public PagedResponse<ExperienceTeaserResponse> browse(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String roleTitle,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Short year,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID viewerId = user == null ? null : user.id();
        return experienceService.browsePublished(viewerId, company, roleTitle, level, year, search, sort, page, size);
    }

    @GetMapping("/{id}")
    public ExperienceViewResponse getOne(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        UUID viewerId = user == null ? null : user.id();
        boolean isAdmin = user != null && user.admin();
        return experienceService.getPublicView(viewerId, isAdmin, id);
    }
}
