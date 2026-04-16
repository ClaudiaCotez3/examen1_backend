package com.example.backend.controller;

import com.example.backend.dto.CaseFileResponseDTO;
import com.example.backend.dto.ProcessHistoryResponseDTO;
import com.example.backend.service.CaseFileService;
import com.example.backend.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/case-files")
@RequiredArgsConstructor
public class CaseFileController {

    private final CaseFileService caseFileService;
    private final WorkflowEngineService workflowEngineService;

    /** Starts a new process instance from a policy version. */
    @PostMapping("/start/{policyVersionId}")
    public ResponseEntity<CaseFileResponseDTO> startProcess(@PathVariable String policyVersionId) {
        CaseFileResponseDTO response = workflowEngineService.startProcess(policyVersionId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Gets a case file by ID with its current active activities. */
    @GetMapping("/{id}")
    public ResponseEntity<CaseFileResponseDTO> getById(@PathVariable String id) {
        return ResponseEntity.ok(caseFileService.getCaseFileById(id));
    }

    /** Lists all case files. Optionally filter by status (ACTIVE or COMPLETED). */
    @GetMapping
    public ResponseEntity<List<CaseFileResponseDTO>> getAll(
            @RequestParam(required = false) String status) {
        if (status != null) {
            return ResponseEntity.ok(caseFileService.getCaseFilesByStatus(status));
        }
        return ResponseEntity.ok(caseFileService.getAllCaseFiles());
    }

    /** Returns the full execution history for a case file. */
    @GetMapping("/{id}/history")
    public ResponseEntity<List<ProcessHistoryResponseDTO>> getHistory(@PathVariable String id) {
        return ResponseEntity.ok(caseFileService.getCaseFileHistory(id));
    }
}
