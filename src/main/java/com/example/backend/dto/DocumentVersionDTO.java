package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Gestión Documental — una versión del historial de un documento. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersionDTO {

    private int version;
    private String fileName;
    private String fileType;
    private Long sizeBytes;
    private String uploadedBy;
    private String uploadedByName;
    private LocalDateTime uploadedAt;
    /** Nota de cambio escrita por quien editó esta versión. */
    private String changeNote;
    /** True para la versión vigente del documento. */
    private boolean current;
    /** False cuando el binario de esta versión ya no está disponible. */
    private boolean hasContent;
}
