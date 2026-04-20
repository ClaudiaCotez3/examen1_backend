package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal acknowledgement returned by {@code POST /api/policies/full-save}.
 *
 * Kept narrow on purpose: callers that need the full denormalized graph hit
 * {@code GET /api/policies/{id}} afterwards. Returning everything here would
 * tempt the client to skip the read and treat the save response as truth,
 * which drifts as soon as anyone else edits the policy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullSaveResponseDTO {

    private String policyId;

    /** Always {@code "saved"} on the success path. */
    private String status;

    /** Convenience: number of the version snapshot minted by this save. */
    private Integer versionNumber;
}
