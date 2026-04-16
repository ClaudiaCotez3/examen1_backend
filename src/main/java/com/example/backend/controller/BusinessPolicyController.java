package com.example.backend.controller;

import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.BusinessPolicyResponseDTO;
import com.example.backend.dto.PolicyVersionResponseDTO;
import com.example.backend.service.BusinessPolicyService;
import com.example.backend.service.PolicyVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class BusinessPolicyController {

    private final BusinessPolicyService businessPolicyService;
    private final PolicyVersionService policyVersionService;

    @PostMapping
    public ResponseEntity<BusinessPolicyResponseDTO> create(@Valid @RequestBody BusinessPolicyRequestDTO request) {
        BusinessPolicyResponseDTO response = businessPolicyService.createPolicy(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<BusinessPolicyResponseDTO>> getAll() {
        return ResponseEntity.ok(businessPolicyService.getAllPolicies());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BusinessPolicyResponseDTO> getById(@PathVariable String id) {
        return ResponseEntity.ok(businessPolicyService.getPolicyById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BusinessPolicyResponseDTO> update(@PathVariable String id,
                                                            @Valid @RequestBody BusinessPolicyRequestDTO request) {
        return ResponseEntity.ok(businessPolicyService.updatePolicy(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        businessPolicyService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }

    /** Persists the full graph (policy + lanes + activities + flows) from the visual modeler. */
    @PostMapping("/full")
    public ResponseEntity<BusinessPolicyResponseDTO> saveFull(@Valid @RequestBody BusinessPolicyRequestDTO request) {
        BusinessPolicyResponseDTO response = businessPolicyService.saveFullPolicyStructure(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<PolicyVersionResponseDTO>> getVersions(@PathVariable String id) {
        return ResponseEntity.ok(policyVersionService.getVersionsByPolicy(id));
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<PolicyVersionResponseDTO> createVersion(@PathVariable String id) {
        PolicyVersionResponseDTO response = policyVersionService.createVersion(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/versions/{versionId}/activate")
    public ResponseEntity<PolicyVersionResponseDTO> activateVersion(@PathVariable String versionId) {
        return ResponseEntity.ok(policyVersionService.activateVersion(versionId));
    }
}
