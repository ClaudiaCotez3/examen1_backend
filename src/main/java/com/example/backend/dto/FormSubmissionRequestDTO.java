package com.example.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Body of {@code POST /api/forms/submit/{activityInstanceId}}.
 * The payload is intentionally a free-form map — it is validated against the
 * activity's {@link com.example.backend.model.FormDefinition} server-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormSubmissionRequestDTO {

    @NotNull(message = "formData is required")
    private Map<String, Object> formData;
}
