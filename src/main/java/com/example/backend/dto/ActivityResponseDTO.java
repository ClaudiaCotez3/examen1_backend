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
    private String type;
    private Boolean requiresForm;
    private FormDefinitionDTO formDefinition;
    private List<String> assignedUserIds;
    private List<String> requirements;
}
