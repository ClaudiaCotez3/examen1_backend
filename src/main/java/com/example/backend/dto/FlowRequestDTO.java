package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowRequestDTO {

    /** Points to source ActivityRequestDTO.clientId (full-policy save) or stored activity id. */
    @NotBlank(message = "Source activity reference is required")
    private String sourceRef;

    /** Points to target ActivityRequestDTO.clientId (full-policy save) or stored activity id. */
    @NotBlank(message = "Target activity reference is required")
    private String targetRef;

    /** LINEAR | CONDITIONAL | PARALLEL | LOOP */
    @NotBlank(message = "Flow type is required")
    private String type;

    private String condition;
}
