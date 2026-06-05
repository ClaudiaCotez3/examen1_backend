package com.example.backend.controller;

import com.example.backend.dto.CaseDocumentDTO;
import com.example.backend.dto.StartCaseRequestDTO;
import com.example.backend.dto.StartCaseResponseDTO;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.service.CaseDocumentService;
import com.example.backend.service.WorkflowEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Consultor-facing endpoint for initiating a new case.
 *
 * The old {@code POST /api/case-files/start/{policyVersionId}} flow required
 * callers to resolve a policy version up-front and did not carry any
 * customer data. This endpoint takes just the policy id plus the structured
 * {@code startFormData} the customer fills — the service validates the data
 * against the policy's {@code startFormDefinition}, resolves (or
 * auto-publishes) the ACTIVE version and boots the workflow.
 */
@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseController {

    private final WorkflowEngineService workflowEngineService;
    private final CaseDocumentService caseDocumentService;

    @PostMapping
    public ResponseEntity<StartCaseResponseDTO> startCase(
            @Valid @RequestBody StartCaseRequestDTO request) {
        StartCaseResponseDTO response = workflowEngineService.startCase(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Gestión Documental (TAREA 4, second half) — receives the REAL binaries
     * of the attachments declared on the start form, right after the case
     * was created. The JSON start payload only carries file metadata; the
     * frontend follows up with this multipart call so the expediente holds
     * the actual content from day one.
     *
     * Lives under /api/cases/** on purpose: it is part of the case-creation
     * flow (CONSULTATION / SUPERVISOR / ADMIN), not an expediente edit, so
     * it is not gated by the per-activity EDITOR permission.
     */
    @PostMapping(value = "/{caseId}/start-form-documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<CaseDocumentDTO>> attachStartFormDocuments(
            @PathVariable String caseId,
            @RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal CustomUserDetails caller) {
        List<CaseDocumentDTO> stored =
                caseDocumentService.attachStartFormContent(caseId, files, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }
}
