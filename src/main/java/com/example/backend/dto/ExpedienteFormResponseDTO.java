package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Gestión Documental — a submitted activity form as shown on the Expediente
 * screen ("Formularios" tab): the answers plus enough context (activity name,
 * schema, author) to render them standalone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpedienteFormResponseDTO {

    private String activityInstanceId;
    private String activityId;
    private String activityName;
    /** Schema of the form (for label resolution); null if no longer available. */
    private FormDefinitionDTO formDefinition;
    private Map<String, Object> formData;
    private String submittedBy;
    private String submittedByName;
    private LocalDateTime submittedAt;
}
