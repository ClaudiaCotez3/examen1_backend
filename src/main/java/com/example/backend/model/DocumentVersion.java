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
 * Gestión Documental — snapshot inmutable de UNA versión de un documento
 * del expediente (colección `versiones_documento`).
 *
 * {@link CaseDocument} apunta siempre a la versión vigente; esta colección
 * conserva TODAS (v1, v2, …) con su binario propio en disco, autor, fecha
 * y la nota de cambio que el editor escribió. Eso habilita la bitácora
 * por documento ("quién editó qué y cuándo") y la descarga de versiones
 * anteriores — append-only, igual que el resto de logs del sistema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "versiones_documento")
@CompoundIndex(name = "doc_version_idx", def = "{ 'documento_id': 1, 'version': -1 }")
public class DocumentVersion {

    @Id
    private ObjectId id;

    @Field("documento_id")
    private ObjectId documentoId;

    @Field("tramite_id")
    private ObjectId tramiteId;

    private Integer version;

    @Field("nombre_archivo")
    private String fileName;

    @Field("tipo_archivo")
    private String fileType;

    @Field("tamano_bytes")
    private Long sizeBytes;

    /** Ruta relativa del binario DE ESTA versión bajo el storage root. */
    @Field("ruta_almacenamiento")
    private String storagePath;

    @Field("subido_por")
    private ObjectId uploadedBy;

    @Field("fecha_subida")
    private LocalDateTime uploadedAt;

    /** Nota de cambio escrita al editar ("se corrigió el monto", etc.). */
    @Field("nota_cambio")
    private String changeNote;
}
