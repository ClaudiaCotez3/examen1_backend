package com.example.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityRequestDTO {

    /** Temporary client-side id used as source/target reference in flows during full-policy save. */
    private String clientId;

    @NotBlank(message = "Activity name is required")
    private String name;

    /** START | TASK | DECISION | END */
    @NotBlank(message = "Activity type is required")
    private String type;

    /** When sent inside a full-policy payload, points to LaneRequestDTO.clientId. */
    @NotBlank(message = "Lane reference is required")
    private String laneRef;

    private Boolean requiresForm;

    /**
     * Schema of the dynamic form attached to the activity.
     * Sent by the frontend after extracting it from the BPMN extension elements.
     */
    @Valid
    private FormDefinitionDTO formDefinition;

    /** Single default operator. Kept for back-compat with older clients. */
    private String assignedUserId;

    /**
     * Multi-assignee variant — preferred. When both fields are present the
     * service treats this list as authoritative and `assignedUserId` as
     * its first element.
     */
    private List<String> assignedUserIds;

    /** Customer-supplied inputs the activity requires (free-text bullets). */
    private List<String> requirements;
}
