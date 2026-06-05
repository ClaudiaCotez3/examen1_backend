package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Gestión Documental — one row of the expediente's audit trail (TAREA 6). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAuditDTO {

    private String id;
    private String caseFileId;
    private String documentId;
    /** UPLOAD | UPDATE | VIEW | DOWNLOAD */
    private String action;
    private String detail;
    private String userId;
    private String userName;
    private LocalDateTime timestamp;
}
