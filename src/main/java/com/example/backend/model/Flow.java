package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Definition-layer sequence flow. Connects two {@link Activity} nodes inside
 * the same {@link BusinessPolicy}.
 *
 * Flow types:
 *   - LINEAR       — unconditional next step.
 *   - CONDITIONAL  — branch out of a DECISION gateway; {@link #condicion}
 *                    carries the expression or label for the branch.
 *   - PARALLEL     — branch out of a parallel gateway (AND split).
 *   - LOOP         — back-edge into an earlier activity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "flujos")
public class Flow {

    @Id
    private ObjectId id;

    @Field("actividad_origen_id")
    private ObjectId actividadOrigenId;

    @Field("actividad_destino_id")
    private ObjectId actividadDestinoId;

    /** LINEAR | CONDITIONAL | PARALLEL | LOOP */
    private String tipo;

    private String condicion;

    /**
     * Human-readable label the admin attached to a branch coming out of
     * a DECISION gateway (e.g. "APROBADO" / "RECHAZADO" or any free text).
     * Null for non-conditional flows. The operator's approval modal
     * surfaces these labels so the decision UI matches the diagram.
     */
    @Field("etiqueta_rama")
    private String branchLabel;
}
