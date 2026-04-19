package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code GET /api/forms/activity/{activityId}}.
 * Carries the form schema together with the activity context so the UI can
 * render and submit without an extra round-trip.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityFormDTO {

    private String activityId;
    private String activityName;
    private Boolean requiresForm;
    private FormDefinitionDTO formDefinition;
}
