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
public class BusinessPolicyResponseDTO {

    private String id;
    private String name;
    private String description;
    private String status;
    /** Monotonically increasing version number of the active definition. */
    private Integer version;
    /** Raw BPMN 2.0 XML for the diagram, when one was persisted. */
    private String bpmnXml;
    /** Process-level prerequisites — see {@link BusinessPolicyRequestDTO#getPrerequisites()}. */
    private List<String> prerequisites;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<LaneResponseDTO> lanes;
    private List<ActivityResponseDTO> activities;
    private List<FlowResponseDTO> flows;
}
