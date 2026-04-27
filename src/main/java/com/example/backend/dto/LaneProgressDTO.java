package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One lane (área / departamento) of the policy together with whether the
 * trámite has already crossed it. Drives the circle timeline in the
 * consultor's detail view.
 *
 * Statuses:
 *   - COMPLETED: every reachable activity in this lane has finished
 *                (or was discarded by a gateway decision).
 *   - CURRENT:   at least one activity in this lane is in_proceso /
 *                en_espera right now — the trámite "lives" here.
 *   - PENDING:   no activity in this lane is active yet (still ahead).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaneProgressDTO {
    private String laneId;
    private String laneName;
    private int position;
    /** COMPLETED | CURRENT | PENDING */
    private String status;
}
