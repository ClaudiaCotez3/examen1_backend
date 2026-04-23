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

    /**
     * Raw BPMN 2.0 XML as exported by the visual designer.
     */
    private String bpmnXml;

    /**
     * Process-level prerequisites — inputs the customer must provide
     * <b>before</b> the process can be initiated. Validated at runtime when
     * a new Procedure is about to be created for this policy. Plain strings
     * so the UI can keep rendering them as a simple bullet list.
     */
    private List<String> prerequisites;

    /** Used only by saveFullPolicyStructure — left null for plain create/update. */
    @Valid
    private List<LaneRequestDTO> lanes;

    @Valid
    private List<ActivityRequestDTO> activities;

    @Valid
    private List<FlowRequestDTO> flows;
}
