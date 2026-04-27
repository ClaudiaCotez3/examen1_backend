package com.example.backend.controller;

import com.example.backend.dto.ConsultationCaseDTO;
import com.example.backend.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer-attention console (Consultas).
 *
 * Lookup-by-customer endpoints used by the consultor to figure out where
 * a returning customer's trámite is parked. Behind the security namespace
 * {@code /api/consultation/**} so CONSULTATION / SUPERVISOR / ADMIN can
 * reach it.
 */
@RestController
@RequestMapping("/api/consultation")
@RequiredArgsConstructor
public class ConsultationController {

    private final ConsultationService consultationService;

    /**
     * Searches trámites whose start-form data matches any of the supplied
     * substrings on the reserved {@code cliente_email / cliente_nombre /
     * cliente_ci} fields. Pass at least one of the params; an empty
     * search returns an empty list.
     */
    @GetMapping("/cases")
    public ResponseEntity<List<ConsultationCaseDTO>> searchCases(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String ci) {
        return ResponseEntity.ok(consultationService.search(email, name, ci));
    }

    /** Single-case detail used to refresh the timeline view. */
    @GetMapping("/cases/{caseId}")
    public ResponseEntity<ConsultationCaseDTO> getCase(@PathVariable String caseId) {
        return ResponseEntity.ok(consultationService.getCase(caseId));
    }
}
