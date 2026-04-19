package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One submission of a dynamic form.
 *
 * Stored as a single document keyed by the activity instance — the previous
 * "one document per field" layout was replaced with a JSON map so that
 * answers travel together with the submission audit metadata
 * (submittedBy / submittedAt) and so that the frontend round-trips a single
 * payload that mirrors the BPMN form definition.
 *
 * The map is intentionally untyped ({@code Map<String, Object>}): the schema
 * is enforced at the service layer against the activity's
 * {@link FormDefinition}, not at the persistence layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "respuestas_formulario")
public class FormResponse {

    @Id
    private ObjectId id;

    /** Activity instance this submission belongs to. Unique — one form per instance. */
    @Field("instancia_actividad_id")
    private ObjectId instanciaActividadId;

    /** Field-name → value map produced by the dynamic form. */
    @Field("data")
    private Map<String, Object> data;

    /** User that submitted the form (assigned operator). */
    @Field("submitted_by")
    private ObjectId submittedBy;

    /** Submission timestamp (server clock). */
    @Field("submitted_at")
    private LocalDateTime submittedAt;
}
