package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Gestión Documental — a document of the trámite's expediente as exposed to
 * clients. {@code storagePath} is intentionally NOT exposed (server-internal);
 * clients download through the dedicated endpoint instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseDocumentDTO {

    private String id;
    private String caseFileId;
    private String fileName;
    private String fileType;
    private Long sizeBytes;
    private Integer version;
    private String uploadedBy;
    /** Friendly name of the uploader, resolved for the Expediente UI. */
    private String uploadedByName;
    private LocalDateTime uploadedAt;
    private LocalDateTime updatedAt;
    /** START_FORM | ACTIVITY | EXPEDIENTE */
    private String source;
    private String sourceLabel;
    /** False for metadata-only rows (start-form attachments without binary). */
    private boolean hasContent;
}
