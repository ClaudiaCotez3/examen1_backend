package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-operator productivity KPIs across every task they ever claimed.
 * Drives the "Rendimiento por operador" panel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorPerformanceDTO {
    private String userId;
    private String fullName;
    private String email;

    /** Total tasks finished (estado finalizado, claimedBy = operator). */
    private long completedCount;
    /** Currently claimed but still en_proceso. */
    private long inProgressCount;
    /** Average time (minutes) the operator holds a task before finishing it. */
    private double avgServiceMinutes;
    /** Median across the whole operator population for the same activities, in minutes. */
    private double teamMedianServiceMinutes;
}
