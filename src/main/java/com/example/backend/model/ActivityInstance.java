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
import java.util.List;

/**
 * Runtime instance of an {@link Activity} for a specific {@link Procedure}
 * (case file). This is the row operators interact with in the Kanban.
 *
 * Assignment model (matches the BPMN designer):
 *   - {@link #assignedUserIds} is the <b>pool</b> of eligible operators,
 *     copied at creation from the {@link Activity} definition. One or many
 *     ids are allowed; when the pool has more than one user any of them can
 *     claim the task, but only the first to do so wins.
 *   - {@link #claimedBy} is the operator who took the task. Null until the
 *     pool is narrowed to a single owner; once set it never changes.
 *
 * State machine (strict — {@link com.example.backend.service.WorkflowEngineService}
 * enforces legal transitions):
 *   WAITING → IN_PROGRESS → COMPLETED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "instancias_actividad")
public class ActivityInstance {

    @Id
    private ObjectId id;

    @Field("tramite_id")
    private ObjectId tramiteId;

    /** Pointer to the {@link Activity} (activityDefinitionId). */
    @Field("actividad_id")
    private ObjectId actividadId;

    /** en_espera | en_proceso | finalizado */
    private String estado;

    /**
     * Pool of operators eligible to claim this task. Copied from the
     * activity definition's {@code assignedUserIds} at instance creation.
     *
     * Backed by the Mongo field {@code usuarios_asignados} (mirrors the
     * field name on {@link Activity}). Empty for non-TASK nodes
     * (START / END / DECISION) — those are auto-advanced by the engine.
     */
    @Field("usuarios_asignados")
    private List<ObjectId> assignedUserIds;

    /**
     * Operator who took the task. Null while WAITING (pool-visible). Set
     * atomically by the "take task" flow together with the transition to
     * IN_PROGRESS. Only this user can complete the task.
     *
     * Kept on the legacy Mongo field {@code asignado_a} so existing
     * documents keep working without migration.
     */
    @Field("asignado_a")
    private ObjectId claimedBy;

    @Field("fecha_creacion")
    private LocalDateTime createdAt;

    @Field("fecha_inicio")
    private LocalDateTime fechaInicio;

    @Field("fecha_fin")
    private LocalDateTime fechaFin;
}
