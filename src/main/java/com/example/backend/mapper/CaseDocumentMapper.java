package com.example.backend.mapper;

import com.example.backend.dto.CaseDocumentDTO;
import com.example.backend.dto.DocumentAuditDTO;
import com.example.backend.model.CaseDocument;
import com.example.backend.model.DocumentAuditLog;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Gestión Documental — entity ↔ DTO mapping for the expediente module. */
@Component
public class CaseDocumentMapper {

    public CaseDocumentDTO toDto(CaseDocument doc, Map<ObjectId, String> userNamesById) {
        return CaseDocumentDTO.builder()
                .id(hex(doc.getId()))
                .caseFileId(hex(doc.getTramiteId()))
                .fileName(doc.getFileName())
                .fileType(doc.getFileType())
                .sizeBytes(doc.getSizeBytes())
                .version(doc.getVersion())
                .uploadedBy(hex(doc.getUploadedBy()))
                .uploadedByName(nameOf(doc.getUploadedBy(), userNamesById))
                .uploadedAt(doc.getUploadedAt())
                .updatedAt(doc.getUpdatedAt())
                .source(doc.getOrigen())
                .sourceLabel(doc.getOrigenDetalle())
                .hasContent(doc.getStoragePath() != null && !doc.getStoragePath().isBlank())
                .build();
    }

    public DocumentAuditDTO toDto(DocumentAuditLog logRow, Map<ObjectId, String> userNamesById) {
        return DocumentAuditDTO.builder()
                .id(hex(logRow.getId()))
                .caseFileId(hex(logRow.getTramiteId()))
                .documentId(hex(logRow.getDocumentoId()))
                .action(logRow.getAccion())
                .detail(logRow.getDetalle())
                .userId(hex(logRow.getUsuarioId()))
                .userName(nameOf(logRow.getUsuarioId(), userNamesById))
                .timestamp(logRow.getFecha())
                .build();
    }

    private String hex(ObjectId id) {
        return id != null ? id.toHexString() : null;
    }

    private String nameOf(ObjectId userId, Map<ObjectId, String> userNamesById) {
        if (userId == null || userNamesById == null) return null;
        return userNamesById.get(userId);
    }
}
