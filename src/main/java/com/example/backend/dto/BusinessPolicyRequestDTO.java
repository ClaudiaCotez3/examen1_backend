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
public class BusinessPolicyRequestDTO {

    @NotBlank(message = "Policy name is required")
    private String name;

    private String description;

    /** DRAFT | ACTIVE — optional on create (defaults to DRAFT). */
    private String status;

    /** Used only by saveFullPolicyStructure — left null for plain create/update. */
    @Valid
    private List<LaneRequestDTO> lanes;

    @Valid
    private List<ActivityRequestDTO> activities;

    @Valid
    private List<FlowRequestDTO> flows;
}
