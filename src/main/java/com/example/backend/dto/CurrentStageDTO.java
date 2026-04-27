package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Snapshot of an activity instance the consultor's "Consultas" panel
 * shows under "Estado actual": the area, activity name, runtime state,
 * the operator who claimed it (if any) and how long it has been there.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentStageDTO {
    private String activityInstanceId;
    private String laneName;
    private String activityName;
    /** WAITING | IN_PROGRESS | BLOCKED */
    private String state;
    private String claimedByName;
    private LocalDateTime since;
}
