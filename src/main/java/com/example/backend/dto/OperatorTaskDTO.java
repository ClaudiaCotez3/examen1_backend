package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight DTO for the operator Kanban.
 *
 * Pre-resolved on the server so the frontend can render a card without
 * additional requests:
 *   - {@code assignedUserIds}  — full pool eligible to claim (visibility set).
 *   - {@code assignedUserId}   — mirror of {@code claimedBy}, kept as a
 *                                single-valued alias for back-compat with
 *                                older frontend code that still reads it.
 *   - {@code assignedUserName} — display name of the claimer, for the
 *                                "Bloqueada — por X" hint on locked cards.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorTaskDTO {

    private String activityInstanceId;
    private String activityId;
    private String activityName;
    private String activityType;

    /** WAITING | IN_PROGRESS | COMPLETED */
    private String status;

    private String caseFileId;
    private String caseFileCode;

    private String laneId;
    private String laneName;

    /** Operators eligible to claim this task (pool copied from the definition). */
    private List<String> assignedUserIds;

    /** Operator who actually claimed the task; null for unclaimed WAITING tasks. */
    private String claimedBy;

    /**
     * Back-compat mirror of {@link #claimedBy}. Older clients expect a
     * singular {@code assignedUserId} field that represents "who owns the
     * task right now". New code should read {@link #claimedBy}.
     */
    private String assignedUserId;

    /** Display name of {@link #claimedBy}; null when the task is unclaimed. */
    private String assignedUserName;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /**
     * True when the activity feeds a DECISION gateway directly (its
     * outgoing flow lands on a {@code tipo == DECISION} node). The
     * operator UI uses this to follow up the form submission with the
     * Aprobar / Rechazar dialog so the engine can decide which branch
     * to keep.
     */
    private boolean requiresDecision;
}
