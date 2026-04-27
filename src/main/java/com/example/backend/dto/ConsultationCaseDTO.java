package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Single trámite as seen by the customer-attention "Consultas" view.
 * Carries enough info to render both the search-result row (cliente,
 * proceso, código, status) and the per-case detail (lane progress
 * timeline + currently active stages).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsultationCaseDTO {

    private String caseId;
    private String code;
    private String policyId;
    private String policyName;

    /** activo | finalizado */
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    private String customerName;
    private String customerEmail;
    private String customerCi;

    /** Lane-by-lane progress in diagram order. Drives the circle timeline. */
    private List<LaneProgressDTO> lanesProgress;

    /** Activity instances currently visible to operators (en_espera/en_proceso). */
    private List<CurrentStageDTO> currentStages;
}
