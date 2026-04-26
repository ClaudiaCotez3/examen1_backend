package com.example.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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
     * Dynamic form the consultor fills when initiating a case for this
     * process. Replaces the deprecated free-text prerequisites list: the
     * customer now provides structured data that travels with the
     * {@link com.example.backend.model.Procedure} from the moment it is
     * created.
     */
    @Valid
    private FormDefinitionDTO startFormDefinition;

    /**
     * Opaque form-js editor schema paired with {@link #startFormDefinition}
     * so the admin re-opens the builder with the exact layout they authored.
     * Accepted as a loose map — the backend stores it verbatim without
     * interpreting it.
     */
    private Map<String, Object> startFormSchema;

    /** Used only by saveFullPolicyStructure — left null for plain create/update. */
    @Valid
    private List<LaneRequestDTO> lanes;

    @Valid
    private List<ActivityRequestDTO> activities;

    @Valid
    private List<FlowRequestDTO> flows;
}
