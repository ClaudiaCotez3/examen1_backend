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

    /**
     * USER | CANDIDATE_USERS | DEPARTMENT.
     * Only meaningful for {@code type == "TASK"} — START/END/DECISION ignore it.
     * When omitted, the service defaults to DEPARTMENT.
     */
    private String assignmentType;

    /**
     * Inferred on the server from the presence of {@link #formDefinition} /
     * {@link #formId}. Kept in the DTO for back-compat; clients should not
     * rely on it.
     */
    private Boolean requiresForm;

    /** Catalog reference to the reusable form; null for approval tasks. */
    private String formId;

    /**
     * Denormalized form schema. When both {@link #formId} and this field are
     * sent, the server trusts {@link #formId} and uses this as a fallback
     * when the referenced form cannot be resolved.
     */
    @Valid
    private FormDefinitionDTO formDefinition;

    /** Singular assignee — kept for back-compat with older clients. */
    private String assignedUserId;

    /**
     * Multi-assignee variant — preferred. Interpretation depends on
     * {@link #assignmentType} (single-user → first id; candidates → pool;
     * department → ignored).
     */
    private List<String> assignedUserIds;

    /**
     * Free-form prerequisites attached to the activity by the BPMN designer
     * via the {@code workflow:requirements} extension attribute. Surfaced to
     * operators at runtime as a checklist; the engine does not enforce them.
     */
    private List<String> requirements;
}
