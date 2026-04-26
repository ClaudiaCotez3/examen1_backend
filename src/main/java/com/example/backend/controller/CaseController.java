package com.example.backend.controller;

import com.example.backend.dto.StartCaseRequestDTO;
import com.example.backend.dto.StartCaseResponseDTO;
import com.example.backend.service.WorkflowEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping
    public ResponseEntity<StartCaseResponseDTO> startCase(
            @Valid @RequestBody StartCaseRequestDTO request) {
        StartCaseResponseDTO response = workflowEngineService.startCase(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
