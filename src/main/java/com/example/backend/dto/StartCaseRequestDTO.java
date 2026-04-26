package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Payload for {@code POST /api/cases}.
 *
 * Consolidates the consultor-facing "start trámite" flow: the caller picks a
 * policy and submits the data captured by that policy's start form. The
 * backend resolves the ACTIVE version, validates the data against
 * {@link com.example.backend.model.BusinessPolicy#getStartFormDefinition()},
 * creates the case and boots the workflow — the client never has to think
 * about policy versioning.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartCaseRequestDTO {

    @NotBlank(message = "policyId is required")
    private String policyId;

    /**
     * Form values keyed by {@code FormField.name}. Shape mirrors the
     * {@code startFormDefinition} on the target policy — scalars for simple
     * fields, nested maps for groups, arrays of maps for dynamic lists.
     * May be null / empty when the policy declares no start form.
     */
    private Map<String, Object> startFormData;
}
