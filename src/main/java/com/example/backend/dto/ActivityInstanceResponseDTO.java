package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityInstanceResponseDTO {

    private String id;
    private String caseFileId;
    private String activityId;
    private String activityName;
    private String activityType;
    /** WAITING | IN_PROGRESS | COMPLETED (Spanish codes mapped by the mapper). */
    private String status;
    /** Operators eligible to claim this task. */
    private List<String> assignedUserIds;
    /** Operator who claimed the task; null while WAITING and pool-visible. */
    private String claimedBy;
    /**
     * Back-compat alias of {@link #claimedBy} — old frontend code expects
     * a singular "assignedUserId" pointing at the current owner.
     */
    private String assignedUserId;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
