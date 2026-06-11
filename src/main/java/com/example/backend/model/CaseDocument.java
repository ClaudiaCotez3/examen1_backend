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
 * Gestión Documental — a document inside a trámite's digital expediente.
 *
 * Every {@link Procedure} (CaseFile) owns its own expediente: the set of
 * {@code CaseDocument} rows sharing its {@code tramite_id}. Documents enter
 * the expediente from three places:
 *   - START_FORM  → attachments declared on the policy's start form when the
 *                   consultor opens the case (TAREA 4).
 *   - ACTIVITY    → attachments submitted on an activity's dynamic form.
 *   - EXPEDIENTE  → direct uploads from the Expediente screen (EDITOR only).
 *
 * The binary lives in the active storage backend — filesystem or AWS S3
 * (see {@link com.example.backend.service.DocumentStorage}) — and the
 * row keeps the {@code storagePath} reference — never the bytes — following
 * the project's "references over blobs" Mongo philosophy. "Actualizar"
 * replaces the binary and bumps {@link #version}; the previous file is kept
 * on disk for traceability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "documentos")
@CompoundIndex(name = "tramite_fecha_idx", def = "{ 'tramite_id': 1, 'fecha_subida': -1 }")
public class CaseDocument {

    @Id
    private ObjectId id;

    /** Trámite (expediente) this document belongs to. */
    @Field("tramite_id")
    private ObjectId tramiteId;

    /** Original file name, e.g. "contrato.pdf". */
    @Field("nombre_archivo")
    private String fileName;

    /** MIME type reported on upload, e.g. "application/pdf". */
    @Field("tipo_archivo")
    private String fileType;

    /** Size in bytes of the CURRENT version. */
    @Field("tamano_bytes")
    private Long sizeBytes;

    /**
     * Relative path of the current binary under the configured storage root.
     * Null when only metadata is known (start-form attachments whose content
     * was never pushed to the server) — such rows are part of the expediente
     * but cannot be downloaded until content is uploaded via "Actualizar".
     */
    @Field("ruta_almacenamiento")
    private String storagePath;

    /** Starts at 1; "Actualizar documento" bumps it and swaps the binary. */
    private Integer version;

    /** User who uploaded the current version (null for anonymous/system). */
    @Field("subido_por")
    private ObjectId uploadedBy;

    @Field("fecha_subida")
    private LocalDateTime uploadedAt;

    /** Last modification (== uploadedAt while version is 1). */
    @Field("fecha_actualizacion")
    private LocalDateTime updatedAt;

    /** START_FORM | ACTIVITY | EXPEDIENTE — where the document came from. */
    private String origen;

    /** Context label (form field / activity name) shown in the UI. */
    @Field("origen_detalle")
    private String origenDetalle;
}
