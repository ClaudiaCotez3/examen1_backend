package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-activity bottleneck KPIs aggregated across every case ever run.
 * Drives the "Cuellos de botella por actividad" panel in the supervisor
 * dashboard.
 *
 * Times are reported in minutes for chart-friendliness.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BottleneckActivityDTO {
    private String activityId;
    private String activityName;
    private String policyName;
    private String laneName;

    /** Average minutes between en_espera and en_proceso (claim latency). */
    private double avgWaitMinutes;
    /** Average minutes between en_proceso and finalizado. */
    private double avgServiceMinutes;
    /** End-to-end (en_espera → finalizado). */
    private double avgLeadMinutes;

    /** Instances currently in en_espera or bloqueada (queue depth right now). */
    private long currentBacklog;
    /** Total instances completed all-time on this activity. */
    private long completedCount;
}
