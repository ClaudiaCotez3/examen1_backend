package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Embedded definition of a single field belonging to an Activity's dynamic form.
 *
 * Stored inside {@link Activity#formDefinition} (which itself is a JSON-shaped
 * sub-document on the actividades collection). It mirrors the field metadata
 * extracted from the BPMN extension elements on the front-end side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormFieldDefinition {

    /** Logical key used both to store the answer and to bind UI controls. */
    private String name;

    /** Human-friendly label rendered next to the input in the UI. */
    private String label;

    /** text | number | date | boolean | select | file */
    private String type;

    /** When true, the value must be present and non-empty in any submission. */
    private Boolean required;

    /** Allowed values for {@code type = "select"}. Ignored for other types. */
    private List<String> options;
}
