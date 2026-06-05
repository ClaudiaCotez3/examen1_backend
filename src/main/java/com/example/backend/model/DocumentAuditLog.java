package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * Gestión Documental — append-only audit trail for the expediente (TAREA 6).
 *
 * One row per documental action. Same philosophy as {@link ProcedureHistory}
 * (the workflow event log): never updated, never deleted, indexed by trámite
 * for the "historial documental" view.
 *
 * Actions: {@code UPLOAD} (carga) · {@code UPDATE} (actualización) ·
 * {@code VIEW} (visualización) · {@code DOWNLOAD} (descarga).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "auditoria_documentos")
@CompoundIndex(name = "tramite_fecha_idx", def = "{ 'tramite_id': 1, 'fecha': -1 }")
public class DocumentAuditLog {

    @Id
    private ObjectId id;

    @Field("tramite_id")
    private ObjectId tramiteId;

    @Field("documento_id")
    private ObjectId documentoId;

    /** User who performed the action (null when no session was available). */
    @Field("usuario_id")
    private ObjectId usuarioId;

    /** UPLOAD | UPDATE | VIEW | DOWNLOAD */
    private String accion;

    /** Human-readable context — usually the file name (+ version). */
    private String detalle;

    private LocalDateTime fecha;
}
