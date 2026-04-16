package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Lightweight DTO for the operator Task Monitor UI.
 * Contains only the fields the operator view needs — no nested entities,
 * no internal state flags, and pre-resolved denormalized names (laneName, activityName)
 * so the frontend can render a card without additional requests.
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

    private String assignedUserId;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
