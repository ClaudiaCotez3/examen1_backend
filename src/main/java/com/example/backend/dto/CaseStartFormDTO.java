package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Snapshot of the start-form data captured at trámite creation time.
 *
 * Returned by {@code GET /api/operator/cases/{caseId}/start-form} so the
 * operator's task modal can show the customer info the consultor filled
 * in before launching the case.
 *
 * Carries both the schema (so the renderer knows the field labels and
 * types) and the structured values keyed by field name.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseStartFormDTO {
    private FormDefinitionDTO definition;
    private Map<String, Object> data;
}
