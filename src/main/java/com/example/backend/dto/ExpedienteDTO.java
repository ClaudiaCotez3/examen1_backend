package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Gestión Documental — the FULL digital expediente of a trámite (TAREA 7).
 *
 * Single aggregate consumed by the "Expediente" screen:
 *   - caseFile      → información del trámite + actividades activas.
 *   - startForm     → formulario inicial (schema + datos del cliente).
 *   - documents     → listado documental del expediente.
 *   - formResponses → formularios respondidos por las actividades.
 *   - history       → historial del workflow (STARTED / COMPLETED / TRANSITION).
 *   - documentAudit → historial documental (UPLOAD / UPDATE / VIEW / DOWNLOAD).
 *   - accessLevel   → permiso documental efectivo del solicitante
 *                     (READER | EDITOR), para que la UI muestre u oculte
 *                     las acciones de subir / actualizar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpedienteDTO {

    private CaseFileResponseDTO caseFile;
    private CaseStartFormDTO startForm;
    private List<CaseDocumentDTO> documents;
    private List<ExpedienteFormResponseDTO> formResponses;
    private List<ProcessHistoryResponseDTO> history;
    private List<DocumentAuditDTO> documentAudit;
    /** READER | EDITOR — effective document permission of the caller. */
    private String accessLevel;
}
