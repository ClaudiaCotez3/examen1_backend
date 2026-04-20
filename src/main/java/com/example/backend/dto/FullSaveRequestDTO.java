package com.example.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for {@code POST /api/policies/full-save}.
 *
 * Two acceptable shapes:
 *   1) {@code structure} present  → backend uses it directly (preferred path,
 *      since the frontend already produced a clean parse)
 *   2) {@code structure} null     → backend re-derives the structure from
 *      {@code bpmnXml} via the BPMN parser
 *
 * In both cases the raw {@code bpmnXml} is persisted on BusinessPolicy so the
 * designer can re-open the diagram with full geometry preserved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullSaveRequestDTO {

    @NotBlank(message = "bpmnXml is required")
    private String bpmnXml;

    /**
     * Structured graph extracted client-side. Optional — when omitted the
     * server runs {@link com.example.backend.service.BpmnXmlParser} on the
     * XML and uses the result.
     */
    @Valid
    private BusinessPolicyRequestDTO structure;
}
