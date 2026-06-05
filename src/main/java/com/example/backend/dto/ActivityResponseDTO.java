package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityResponseDTO {

    private String id;
    private String policyId;
    private String laneId;
    private String name;
    /** START | TASK | DECISION | END */
    private String type;
    /** USER | CANDIDATE_USERS | DEPARTMENT — null for non-TASK nodes. */
    private String assignmentType;
    private Boolean requiresForm;
    /** Catalog id of the attached form; null for approval / non-task activities. */
    private String formId;
    private FormDefinitionDTO formDefinition;
    private List<String> assignedUserIds;
    /** READER | EDITOR — document access over the expediente (null for non-TASK nodes). */
    private String documentAccess;
}
