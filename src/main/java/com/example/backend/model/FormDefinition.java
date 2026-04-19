package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema of a dynamic form attached to an Activity.
 *
 * Embedded as a sub-document inside the activity itself. The shape mirrors the
 * JSON the BPMN modelling layer emits, e.g.
 * <pre>
 * {
 *   "fields": [
 *     { "name": "customerName", "type": "text", "required": true },
 *     { "name": "installationDate", "type": "date", "required": true }
 *   ]
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormDefinition {

    private List<FormFieldDefinition> fields;
}
