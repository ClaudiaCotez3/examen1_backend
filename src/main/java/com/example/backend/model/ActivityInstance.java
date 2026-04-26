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

    @Field("actividad_id")
    private ObjectId actividadId;

    /** en_espera | en_proceso | finalizado */
    private String estado;

    /**
     * Operator who claimed the task. Null while the instance is still in the
     * pool (en_espera and unclaimed). Persisted under {@code asignado_a} for
     * back-compat with documents written before the rename.
     */
    @Field("asignado_a")
    private ObjectId claimedBy;

    /**
     * Snapshot of the eligible operator pool taken from the activity
     * definition at instance creation time. Frozen on the instance so changes
     * to the policy after a case starts don't reshuffle who can claim a
     * pending task.
     */
    @Field("usuarios_asignados")
    private List<ObjectId> assignedUserIds;

    @Field("fecha_creacion")
    private LocalDateTime createdAt;

    @Field("fecha_inicio")
    private LocalDateTime fechaInicio;

    @Field("fecha_fin")
    private LocalDateTime fechaFin;
}
