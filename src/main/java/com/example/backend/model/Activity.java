package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

/**
 * Definition-layer activity node. Types:
 *   - START    — single start event
 *   - TASK     — HUMAN_TASK (optionally FORM-backed, otherwise APPROVAL)
 *   - DECISION — exclusive / parallel gateway
 *   - END      — single end event
 *
 * Runtime state (claim, completion, assignee) lives in
 * {@link ActivityInstance}; this entity is purely the design-time template.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "actividades")
public class Activity {

    @Id
    private ObjectId id;

    @Field("politica_id")
    private ObjectId politicaId;

    /** Lane / Department this activity belongs to. */
    @Field("calle_id")
    private ObjectId calleId;

    private String nombre;

    /** START | TASK | DECISION | END */
    private String tipo;

    /**
     * How the workflow engine should assign this task at runtime.
     *   - USER             → exactly one operator from {@link #assignedUserIds}.
     *   - CANDIDATE_USERS  → pool of operators; any member can pick it up.
     *   - DEPARTMENT       → any operator in the owning department is eligible.
     * Only meaningful for {@code tipo == TASK}; ignored for START/END/DECISION.
     */
    @Field("tipo_asignacion")
    private String assignmentType;

    @Field("requiere_formulario")
    private Boolean requiereFormulario;

    /**
     * Catalog reference to a {@link Form} in the reusable form library. Only
     * set when the activity is form-backed (FORM_TASK); null for approval
     * tasks. Kept alongside {@link #formDefinition} so reads don't require a
     * join but authoring edits to the catalog still propagate on next save.
     */
    @Field("formulario_id")
    private ObjectId formId;

    /**
     * Embedded JSON-shaped form schema. Duplicated from the catalog at save
     * time so a runtime read is a single document; the {@link #formId}
     * points back to the canonical entry.
     */
    @Field("definicion_formulario")
    private FormDefinition formDefinition;

    /**
     * Operators (User ids) authorized to pick up this activity at runtime.
     * Semantics depend on {@link #assignmentType}:
     *   - USER → single id, strict ownership.
     *   - CANDIDATE_USERS → pool of eligible operators.
     *   - DEPARTMENT → ignored (lane membership governs eligibility).
     */
    @Field("usuarios_asignados")
    private List<String> assignedUserIds;
}
