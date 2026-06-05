package com.example.backend.controller;

import com.example.backend.dto.CaseDocumentDTO;
import com.example.backend.dto.DocumentAuditDTO;
import com.example.backend.dto.DocumentVersionDTO;
import com.example.backend.dto.ExpedienteDTO;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.service.CaseDocumentService;
import com.example.backend.service.CaseDocumentService.DocumentContent;
import com.example.backend.service.OnlyOfficeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Gestión Documental — REST API of the digital expediente (TAREAS 5 y 7).
 *
 * Mounted under the existing case-files resource so role access reuses the
 * SecurityConfig rule for {@code /api/case-files/**} (OPERATOR, SUPERVISOR,
 * ADMIN, CONSULTATION). Fine-grained documental permissions (READER vs
 * EDITOR per the current activity's "Acceso a documentos") are enforced in
 * {@link CaseDocumentService}.
 *
 *   GET    /api/case-files/{id}/expediente                      → expediente completo
 *   GET    /api/case-files/{id}/documents                       → listado documental
 *   POST   /api/case-files/{id}/documents          (multipart)  → subir documentos (EDITOR)
 *   PUT    /api/case-files/{id}/documents/{docId}  (multipart)  → actualizar / nueva versión (EDITOR)
 *   GET    /api/case-files/{id}/documents/{docId}/view          → visualizar inline   (audita VIEW)
 *   GET    /api/case-files/{id}/documents/{docId}/download      → descargar adjunto   (audita DOWNLOAD)
 *   GET    /api/case-files/{id}/documents/audit                 → historial documental
 */
@RestController
@RequestMapping("/api/case-files/{caseFileId}")
@RequiredArgsConstructor
public class CaseDocumentController {

    private final CaseDocumentService caseDocumentService;
    private final OnlyOfficeService onlyOfficeService;

    /** TAREA 7 — full expediente aggregate feeding the "Expediente" screen. */
    @GetMapping("/expediente")
    public ResponseEntity<ExpedienteDTO> getExpediente(
            @PathVariable String caseFileId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(caseDocumentService.getExpediente(caseFileId, caller));
    }

    /** Lists the documents of the trámite's expediente. */
    @GetMapping("/documents")
    public ResponseEntity<List<CaseDocumentDTO>> listDocuments(
            @PathVariable String caseFileId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(caseDocumentService.listDocuments(caseFileId, caller));
    }

    /** Uploads one or more documents (requires EDITOR on the current activity). */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<CaseDocumentDTO>> uploadDocuments(
            @PathVariable String caseFileId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String sourceLabel,
            @AuthenticationPrincipal CustomUserDetails caller) {
        List<CaseDocumentDTO> stored = caseDocumentService
                .uploadDocuments(caseFileId, files, source, sourceLabel, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(stored);
    }

    /**
     * "Editar documento": reemplaza el contenido con la versión editada
     * (flujo descargar → editar localmente → subir), registrando la nota
     * de cambio en la bitácora. Requiere EDITOR.
     */
    @PutMapping(value = "/documents/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaseDocumentDTO> updateDocument(
            @PathVariable String caseFileId,
            @PathVariable String documentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "note", required = false) String note,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(
                caseDocumentService.updateDocument(caseFileId, documentId, file, note, caller));
    }

    /**
     * Configuración firmada de DocsAPI.DocEditor (OnlyOffice) para editar
     * o ver el documento. Autenticado con la sesión normal del usuario:
     * el modo (edit/view) deriva de los permisos READER/EDITOR existentes.
     */
    @GetMapping("/documents/{documentId}/onlyoffice-config")
    public ResponseEntity<Map<String, Object>> getOnlyOfficeConfig(
            @PathVariable String caseFileId,
            @PathVariable String documentId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(
                onlyOfficeService.buildEditorConfig(caseFileId, documentId, caller));
    }

    /** Bitácora por documento: historial completo de versiones. */
    @GetMapping("/documents/{documentId}/versions")
    public ResponseEntity<List<DocumentVersionDTO>> getDocumentVersions(
            @PathVariable String caseFileId,
            @PathVariable String documentId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(
                caseDocumentService.getDocumentVersions(caseFileId, documentId, caller));
    }

    /** Descarga el binario de una versión histórica (audita DOWNLOAD). */
    @GetMapping("/documents/{documentId}/versions/{version}/download")
    public ResponseEntity<byte[]> downloadDocumentVersion(
            @PathVariable String caseFileId,
            @PathVariable String documentId,
            @PathVariable int version,
            @AuthenticationPrincipal CustomUserDetails caller) {
        DocumentContent content = caseDocumentService
                .getVersionContent(caseFileId, documentId, version, caller);
        return buildBinaryResponse(content, true);
    }

    /** Streams the document inline (visualización — audited as VIEW). */
    @GetMapping("/documents/{documentId}/view")
    public ResponseEntity<byte[]> viewDocument(
            @PathVariable String caseFileId,
            @PathVariable String documentId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        DocumentContent content = caseDocumentService.getDocumentContent(
                caseFileId, documentId, CaseDocumentService.ACTION_VIEW, caller);
        return buildBinaryResponse(content, false);
    }

    /** Streams the document as an attachment (descarga — audited as DOWNLOAD). */
    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable String caseFileId,
            @PathVariable String documentId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        DocumentContent content = caseDocumentService.getDocumentContent(
                caseFileId, documentId, CaseDocumentService.ACTION_DOWNLOAD, caller);
        return buildBinaryResponse(content, true);
    }

    /** Historial documental — the expediente's audit trail (TAREA 6). */
    @GetMapping("/documents/audit")
    public ResponseEntity<List<DocumentAuditDTO>> getDocumentAudit(
            @PathVariable String caseFileId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(caseDocumentService.getDocumentAudit(caseFileId, caller));
    }

    private ResponseEntity<byte[]> buildBinaryResponse(DocumentContent content, boolean attachment) {
        ContentDisposition disposition = ContentDisposition
                .builder(attachment ? "attachment" : "inline")
                .filename(content.fileName() != null ? content.fileName() : "documento",
                        StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.set(HttpHeaders.CONTENT_TYPE, content.fileType());
        return ResponseEntity.ok().headers(headers).body(content.bytes());
    }
}
