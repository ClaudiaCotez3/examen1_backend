package com.example.backend.service;

import com.example.backend.dto.CaseDocumentDTO;
import com.example.backend.dto.DocumentAuditDTO;
import com.example.backend.dto.DocumentVersionDTO;
import com.example.backend.dto.ExpedienteDTO;
import com.example.backend.dto.ExpedienteFormResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.CaseDocumentMapper;
import com.example.backend.mapper.FormMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.CaseDocument;
import com.example.backend.model.DocumentAuditLog;
import com.example.backend.model.DocumentVersion;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.FormFieldDefinition;
import com.example.backend.model.FormResponse;
import com.example.backend.model.Procedure;
import com.example.backend.model.User;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.CaseDocumentRepository;
import com.example.backend.repository.DocumentAuditLogRepository;
import com.example.backend.repository.DocumentVersionRepository;
import com.example.backend.repository.FormResponseRepository;
import com.example.backend.repository.ProcedureRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.RoleName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Gestión Documental — core service of the digital expediente.
 *
 * Every {@link Procedure} (trámite) behaves as a full digital expediente:
 * documents, submitted forms and workflow history hang off its id. This
 * service owns:
 *
 *   - Document CRUD (list / upload / update / content) with versioning.
 *   - Permission enforcement per the activity-level "Acceso a documentos"
 *     configuration (READER | EDITOR) authored in the policy designer:
 *       · ADMIN / SUPERVISOR → EDITOR siempre (supervisión).
 *       · CONSULTATION       → READER.
 *       · OPERATOR           → EDITOR sólo si alguna de sus actividades
 *         vigentes (reclamadas o elegibles) en el trámite fue configurada
 *         como EDITOR; READER en caso contrario.
 *   - Audit trail (TAREA 6): UPLOAD / UPDATE / VIEW / DOWNLOAD.
 *   - Start-form attachment registration at case creation (TAREA 4).
 *   - The full-expediente aggregate (TAREA 7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseDocumentService {

    public static final String ACCESS_READER = "READER";
    public static final String ACCESS_EDITOR = "EDITOR";

    public static final String ACTION_UPLOAD = "UPLOAD";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_VIEW = "VIEW";
    public static final String ACTION_DOWNLOAD = "DOWNLOAD";

    private static final String SOURCE_START_FORM = "START_FORM";
    private static final String SOURCE_ACTIVITY = "ACTIVITY";
    private static final String SOURCE_EXPEDIENTE = "EXPEDIENTE";
    private static final Set<String> VALID_SOURCES =
            Set.of(SOURCE_START_FORM, SOURCE_ACTIVITY, SOURCE_EXPEDIENTE);

    /** ActivityInstance states that still grant the operator documental access. */
    private static final Set<String> OPEN_INSTANCE_STATES = Set.of("en_espera", "en_proceso");

    private final CaseDocumentRepository caseDocumentRepository;
    private final DocumentAuditLogRepository documentAuditLogRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ProcedureRepository procedureRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final FormResponseRepository formResponseRepository;

    private final DocumentStorageService documentStorageService;
    private final CaseFileService caseFileService;
    private final OperatorService operatorService;
    private final CaseDocumentMapper caseDocumentMapper;
    private final FormMapper formMapper;

    // ── Permisos (TAREA 5 — validación según actividad actual) ───────────

    /**
     * Effective document permission of the caller over a trámite. See the
     * class doc for the role matrix. Activities without an explicit
     * configuration count as READER (the designer's safe default).
     */
    public String resolveAccessLevel(ObjectId tramiteId, CustomUserDetails caller) {
        if (caller == null) return ACCESS_READER;
        String role = caller.getRoleName();
        if (RoleName.ADMIN.equals(role) || RoleName.SUPERVISOR.equals(role)) {
            return ACCESS_EDITOR;
        }
        if (!RoleName.OPERATOR.equals(role)) {
            return ACCESS_READER;
        }
        ObjectId userId = caller.getId() != null && ObjectId.isValid(caller.getId())
                ? new ObjectId(caller.getId())
                : null;
        if (userId == null) return ACCESS_READER;

        List<ActivityInstance> instances = activityInstanceRepository.findByTramiteId(tramiteId);
        List<ObjectId> myActivityIds = instances.stream()
                .filter(i -> OPEN_INSTANCE_STATES.contains(i.getEstado()))
                .filter(i -> isEligible(i, userId))
                .map(ActivityInstance::getActividadId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (myActivityIds.isEmpty()) return ACCESS_READER;

        boolean isEditor = activityRepository.findAllById(myActivityIds).stream()
                .anyMatch(a -> ACCESS_EDITOR.equals(a.getDocumentAccess()));
        return isEditor ? ACCESS_EDITOR : ACCESS_READER;
    }

    /** Operator is eligible when they claimed the instance or sit in its pool. */
    private boolean isEligible(ActivityInstance instance, ObjectId userId) {
        if (userId.equals(instance.getClaimedBy())) return true;
        List<ObjectId> pool = instance.getAssignedUserIds();
        if (pool != null && pool.contains(userId)) return true;
        // Fallback for instances created before the runtime pool was copied:
        // check the activity definition's assignee list (stored as hex ids).
        if (instance.getActividadId() != null) {
            return activityRepository.findById(instance.getActividadId())
                    .map(a -> a.getAssignedUserIds() != null
                            && a.getAssignedUserIds().contains(userId.toHexString()))
                    .orElse(false);
        }
        return false;
    }

    private void assertCanRead(ObjectId tramiteId, CustomUserDetails caller) {
        // Every authenticated role that reaches /api/case-files/** can read
        // the expediente; the granular gate is on WRITE operations.
        if (caller == null) {
            throw new AccessDeniedException("Authentication required");
        }
    }

    private void assertCanEdit(ObjectId tramiteId, CustomUserDetails caller) {
        if (!ACCESS_EDITOR.equals(resolveAccessLevel(tramiteId, caller))) {
            throw new AccessDeniedException(
                    "Document editing requires EDITOR access on the current activity");
        }
    }

    // ── Listado (TAREA 5) ─────────────────────────────────────────────────

    public List<CaseDocumentDTO> listDocuments(String caseFileId, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanRead(caseFile.getId(), caller);
        List<CaseDocument> docs =
                caseDocumentRepository.findByTramiteIdOrderByUpdatedAtDesc(caseFile.getId());
        Map<ObjectId, String> names = userNames(docs.stream().map(CaseDocument::getUploadedBy));
        return docs.stream().map(d -> caseDocumentMapper.toDto(d, names)).toList();
    }

    // ── Subida (TAREA 5 — EDITOR) ─────────────────────────────────────────

    public List<CaseDocumentDTO> uploadDocuments(
            String caseFileId,
            List<MultipartFile> files,
            String source,
            String sourceLabel,
            CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanEdit(caseFile.getId(), caller);
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one file is required");
        }

        ObjectId userId = callerId(caller);
        LocalDateTime now = LocalDateTime.now();
        String resolvedSource = normalizeSource(source);
        List<CaseDocument> stored = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            CaseDocument doc = caseDocumentRepository.save(CaseDocument.builder()
                    .tramiteId(caseFile.getId())
                    .fileName(file.getOriginalFilename())
                    .fileType(file.getContentType())
                    .sizeBytes(file.getSize())
                    .version(1)
                    .uploadedBy(userId)
                    .uploadedAt(now)
                    .updatedAt(now)
                    .origen(resolvedSource)
                    .origenDetalle(sourceLabel)
                    .build());
            String path = documentStorageService.store(
                    caseFile.getId().toHexString(), doc.getId().toHexString(), 1, file);
            doc.setStoragePath(path);
            caseDocumentRepository.save(doc);
            recordVersion(doc, userId, now, "Carga inicial");
            audit(caseFile.getId(), doc.getId(), userId, ACTION_UPLOAD,
                    doc.getFileName() + " (v1)", now);
            stored.add(doc);
        }
        if (stored.isEmpty()) {
            throw new BadRequestException("All provided files were empty");
        }
        Map<ObjectId, String> names = userNames(stored.stream().map(CaseDocument::getUploadedBy));
        return stored.stream().map(d -> caseDocumentMapper.toDto(d, names)).toList();
    }

    // ── Actualización / nueva versión (TAREA 5 — EDITOR) ──────────────────

    public CaseDocumentDTO updateDocument(
            String caseFileId, String documentId, MultipartFile file,
            String changeNote, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanEdit(caseFile.getId(), caller);
        CaseDocument doc = findDocumentOrThrow(caseFile.getId(), documentId);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File content is required");
        }

        ObjectId userId = callerId(caller);
        LocalDateTime now = LocalDateTime.now();
        // Garantiza que la versión saliente exista en el historial antes de
        // reemplazarla (documentos creados antes del versionado persistente).
        ensureVersionHistory(doc);

        int nextVersion = (doc.getVersion() == null ? 1 : doc.getVersion()) + 1;
        String path = documentStorageService.store(
                caseFile.getId().toHexString(), doc.getId().toHexString(), nextVersion, file);

        // The previous binary stays on disk (version-suffixed) for traceability.
        doc.setFileName(file.getOriginalFilename());
        doc.setFileType(file.getContentType());
        doc.setSizeBytes(file.getSize());
        doc.setStoragePath(path);
        doc.setVersion(nextVersion);
        doc.setUpdatedAt(now);
        doc.setUploadedBy(userId != null ? userId : doc.getUploadedBy());
        caseDocumentRepository.save(doc);

        String note = changeNote != null && !changeNote.isBlank()
                ? changeNote.trim() : null;
        recordVersion(doc, userId, now, note);
        audit(caseFile.getId(), doc.getId(), userId, ACTION_UPDATE,
                doc.getFileName() + " (v" + nextVersion + ")"
                        + (note != null ? " — " + note : ""), now);

        Map<ObjectId, String> names = userNames(Stream.of(doc.getUploadedBy()));
        return caseDocumentMapper.toDto(doc, names);
    }

    // ── Integración OnlyOffice (server-to-server, autorizada por token) ───

    /**
     * Binario para el Document Server de OnlyOffice. El DS no tiene la
     * sesión del usuario: la autorización es el token firmado que emitió
     * {@code OnlyOfficeService} al construir la configuración (ya validado
     * por el controlador). Se audita como VIEW atribuida al usuario que
     * abrió el editor.
     */
    public DocumentContent getContentForOnlyOffice(
            String caseFileId, String documentId, ObjectId userId) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        CaseDocument doc = findDocumentOrThrow(caseFile.getId(), documentId);
        if (doc.getStoragePath() == null || doc.getStoragePath().isBlank()
                || !documentStorageService.exists(doc.getStoragePath())) {
            throw new BadRequestException("El documento no tiene contenido almacenado.");
        }
        byte[] bytes = documentStorageService.load(doc.getStoragePath());
        audit(caseFile.getId(), doc.getId(), userId, ACTION_VIEW,
                doc.getFileName() + " (v" + doc.getVersion() + ", abierto en OnlyOffice)",
                LocalDateTime.now());
        String type = doc.getFileType() != null && !doc.getFileType().isBlank()
                ? doc.getFileType() : "application/octet-stream";
        return new DocumentContent(bytes, doc.getFileName(), type);
    }

    /**
     * Guardado proveniente del callback de OnlyOffice: persiste los bytes
     * editados como NUEVA VERSIÓN reutilizando exactamente el mismo
     * versionado inmutable + auditoría del flujo manual (updateDocument).
     */
    public CaseDocument saveVersionFromOnlyOffice(
            String caseFileId, String documentId, byte[] bytes, ObjectId userId) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        CaseDocument doc = findDocumentOrThrow(caseFile.getId(), documentId);
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("Empty document payload from OnlyOffice");
        }

        LocalDateTime now = LocalDateTime.now();
        ensureVersionHistory(doc);

        int nextVersion = (doc.getVersion() == null ? 1 : doc.getVersion()) + 1;
        String path = documentStorageService.storeBytes(
                caseFile.getId().toHexString(), doc.getId().toHexString(),
                nextVersion, doc.getFileName(), bytes);

        doc.setSizeBytes((long) bytes.length);
        doc.setStoragePath(path);
        doc.setVersion(nextVersion);
        doc.setUpdatedAt(now);
        if (userId != null) doc.setUploadedBy(userId);
        caseDocumentRepository.save(doc);

        String note = "Edición en OnlyOffice";
        recordVersion(doc, userId, now, note);
        audit(caseFile.getId(), doc.getId(), userId, ACTION_UPDATE,
                doc.getFileName() + " (v" + nextVersion + ") — " + note, now);
        log.info("OnlyOffice save: case={} doc={} -> v{}", caseFileId, documentId, nextVersion);
        return doc;
    }

    /** Documento + trámite validados (para construir la config del editor). */
    public CaseDocument requireDocument(String caseFileId, String documentId) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        return findDocumentOrThrow(caseFile.getId(), documentId);
    }

    // ── Historial de versiones (bitácora por documento) ───────────────────

    /** Versiones de un documento, la más reciente primero. */
    public List<DocumentVersionDTO> getDocumentVersions(
            String caseFileId, String documentId, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanRead(caseFile.getId(), caller);
        CaseDocument doc = findDocumentOrThrow(caseFile.getId(), documentId);
        ensureVersionHistory(doc);

        List<DocumentVersion> versions =
                documentVersionRepository.findByDocumentoIdOrderByVersionDesc(doc.getId());
        Map<ObjectId, String> names = userNames(versions.stream()
                .map(DocumentVersion::getUploadedBy));
        int currentVersion = doc.getVersion() != null ? doc.getVersion() : 1;
        return versions.stream()
                .map(v -> DocumentVersionDTO.builder()
                        .version(v.getVersion() != null ? v.getVersion() : 1)
                        .fileName(v.getFileName())
                        .fileType(v.getFileType())
                        .sizeBytes(v.getSizeBytes())
                        .uploadedBy(v.getUploadedBy() != null
                                ? v.getUploadedBy().toHexString() : null)
                        .uploadedByName(names.get(v.getUploadedBy()))
                        .uploadedAt(v.getUploadedAt())
                        .changeNote(v.getChangeNote())
                        .current(v.getVersion() != null && v.getVersion() == currentVersion)
                        .hasContent(documentStorageService.exists(v.getStoragePath()))
                        .build())
                .toList();
    }

    /** Descarga el binario de UNA versión específica (auditado). */
    public DocumentContent getVersionContent(
            String caseFileId, String documentId, int version, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanRead(caseFile.getId(), caller);
        CaseDocument doc = findDocumentOrThrow(caseFile.getId(), documentId);
        ensureVersionHistory(doc);

        DocumentVersion snapshot = documentVersionRepository
                .findByDocumentoIdAndVersion(doc.getId(), version)
                .orElseThrow(() -> new BadRequestException(
                        "El documento no tiene una versión " + version));
        if (!documentStorageService.exists(snapshot.getStoragePath())) {
            throw new BadRequestException(
                    "El contenido de la versión " + version + " ya no está disponible.");
        }
        byte[] bytes = documentStorageService.load(snapshot.getStoragePath());
        audit(caseFile.getId(), doc.getId(), callerId(caller), ACTION_DOWNLOAD,
                snapshot.getFileName() + " (v" + version + ", versión histórica)",
                LocalDateTime.now());
        String type = snapshot.getFileType() != null && !snapshot.getFileType().isBlank()
                ? snapshot.getFileType() : "application/octet-stream";
        return new DocumentContent(bytes, snapshot.getFileName(), type);
    }

    /** Snapshot inmutable de la versión vigente del documento. */
    private void recordVersion(CaseDocument doc, ObjectId userId,
                               LocalDateTime when, String changeNote) {
        documentVersionRepository.save(DocumentVersion.builder()
                .documentoId(doc.getId())
                .tramiteId(doc.getTramiteId())
                .version(doc.getVersion() != null ? doc.getVersion() : 1)
                .fileName(doc.getFileName())
                .fileType(doc.getFileType())
                .sizeBytes(doc.getSizeBytes())
                .storagePath(doc.getStoragePath())
                .uploadedBy(userId)
                .uploadedAt(when)
                .changeNote(changeNote)
                .build());
    }

    /**
     * Backfill perezoso: documentos creados antes del versionado persistente
     * obtienen su fila de historial con los datos vigentes la primera vez
     * que alguien consulta o edita su historial. Idempotente.
     */
    private void ensureVersionHistory(CaseDocument doc) {
        if (doc.getStoragePath() == null || doc.getStoragePath().isBlank()) return;
        if (documentVersionRepository.existsByDocumentoId(doc.getId())) return;
        recordVersion(doc, doc.getUploadedBy(),
                doc.getUpdatedAt() != null ? doc.getUpdatedAt() : doc.getUploadedAt(),
                null);
    }

    // ── Contenido: visualizar / descargar (TAREA 5 + auditoría TAREA 6) ──

    /** Binary + metadata returned to the controller for streaming. */
    public record DocumentContent(byte[] bytes, String fileName, String fileType) { }

    /**
     * Loads the binary of a document, recording the access on the audit
     * trail. {@code action} must be {@link #ACTION_VIEW} (inline preview)
     * or {@link #ACTION_DOWNLOAD} (attachment).
     */
    public DocumentContent getDocumentContent(
            String caseFileId, String documentId, String action, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanRead(caseFile.getId(), caller);
        CaseDocument doc = findDocumentOrThrow(caseFile.getId(), documentId);

        if (doc.getStoragePath() == null || doc.getStoragePath().isBlank()
                || !documentStorageService.exists(doc.getStoragePath())) {
            throw new BadRequestException(
                    "This document has no stored content yet. Upload a new version to attach the file.");
        }

        byte[] bytes = documentStorageService.load(doc.getStoragePath());
        String resolvedAction = ACTION_DOWNLOAD.equals(action) ? ACTION_DOWNLOAD : ACTION_VIEW;
        audit(caseFile.getId(), doc.getId(), callerId(caller), resolvedAction,
                doc.getFileName() + " (v" + doc.getVersion() + ")", LocalDateTime.now());

        String type = doc.getFileType() != null && !doc.getFileType().isBlank()
                ? doc.getFileType()
                : "application/octet-stream";
        return new DocumentContent(bytes, doc.getFileName(), type);
    }

    // ── Historial documental (TAREAS 5 y 6) ───────────────────────────────

    public List<DocumentAuditDTO> getDocumentAudit(String caseFileId, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanRead(caseFile.getId(), caller);
        List<DocumentAuditLog> rows =
                documentAuditLogRepository.findByTramiteIdOrderByFechaDesc(caseFile.getId());
        Map<ObjectId, String> names = userNames(rows.stream().map(DocumentAuditLog::getUsuarioId));
        return rows.stream().map(r -> caseDocumentMapper.toDto(r, names)).toList();
    }

    // ── Inicio de trámite (TAREA 4) ───────────────────────────────────────

    /**
     * Called by the workflow engine right after a case is created: registers
     * every attachment declared on the start form as part of the expediente.
     *
     * The Angular start form sends file METADATA ({@code [{name,size,type}]})
     * inside {@code startFormData} — the binaries are not part of the JSON
     * payload — so the expediente rows are created content-pending
     * ({@code storagePath = null}). An EDITOR can attach the real binary
     * later via "Actualizar"; meanwhile the document is visible, versioned
     * and audited like any other.
     */
    public void registerStartFormDocuments(Procedure caseFile, FormDefinition startFormDefinition) {
        if (caseFile == null || startFormDefinition == null
                || startFormDefinition.getFields() == null
                || caseFile.getStartFormData() == null) {
            return;
        }
        ObjectId userId = currentUserIdOrNull();
        LocalDateTime now = caseFile.getFechaInicio() != null
                ? caseFile.getFechaInicio()
                : LocalDateTime.now();

        for (FormFieldDefinition field : startFormDefinition.getFields()) {
            if (field == null || !"file".equalsIgnoreCase(String.valueOf(field.getType()))) {
                continue;
            }
            Object raw = caseFile.getStartFormData().get(field.getName());
            if (!(raw instanceof Collection<?> entries)) continue;

            String label = field.getLabel() != null && !field.getLabel().isBlank()
                    ? field.getLabel()
                    : field.getName();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> meta)) continue;
                String fileName = stringOf(meta.get("name"), "documento");
                Long size = longOf(meta.get("size"));
                String type = stringOf(meta.get("type"), null);

                CaseDocument doc = caseDocumentRepository.save(CaseDocument.builder()
                        .tramiteId(caseFile.getId())
                        .fileName(fileName)
                        .fileType(type)
                        .sizeBytes(size)
                        .storagePath(null) // metadata-only until content is pushed
                        .version(1)
                        .uploadedBy(userId)
                        .uploadedAt(now)
                        .updatedAt(now)
                        .origen(SOURCE_START_FORM)
                        .origenDetalle("Formulario inicial · " + label)
                        .build());
                audit(caseFile.getId(), doc.getId(), userId, ACTION_UPLOAD,
                        fileName + " (formulario inicial)", now);
            }
        }
    }

    /**
     * Second half of TAREA 4: receives the REAL binaries of the start-form
     * attachments right after the case was created.
     *
     * The consultor's browser first sends the JSON payload (metadata only —
     * {@link #registerStartFormDocuments} turns it into content-pending
     * expediente rows) and then pushes the files here. Each binary is matched
     * to its pending row by name (+ size when known); unmatched files are
     * registered as fresh START_FORM documents.
     *
     * Intentionally NOT gated by the EDITOR permission: this call is part of
     * the case-creation flow (CONSULTATION role), not an expediente edit.
     * The endpoint lives under /api/cases/** which already restricts callers
     * to CONSULTATION / SUPERVISOR / ADMIN.
     */
    public List<CaseDocumentDTO> attachStartFormContent(
            String caseFileId, List<MultipartFile> files, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one file is required");
        }
        ObjectId userId = callerId(caller);
        LocalDateTime now = LocalDateTime.now();

        List<CaseDocument> pending = caseDocumentRepository
                .findByTramiteIdOrderByUpdatedAtDesc(caseFile.getId()).stream()
                .filter(d -> SOURCE_START_FORM.equals(d.getOrigen()))
                .filter(d -> d.getStoragePath() == null || d.getStoragePath().isBlank())
                .collect(Collectors.toCollection(ArrayList::new));

        List<CaseDocument> touched = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            CaseDocument target = matchPending(pending, file);
            if (target != null) {
                pending.remove(target);
                String path = documentStorageService.store(
                        caseFile.getId().toHexString(), target.getId().toHexString(),
                        target.getVersion() != null ? target.getVersion() : 1, file);
                target.setStoragePath(path);
                target.setFileType(file.getContentType() != null
                        ? file.getContentType() : target.getFileType());
                target.setSizeBytes(file.getSize());
                target.setUpdatedAt(now);
                if (target.getUploadedBy() == null) target.setUploadedBy(userId);
                caseDocumentRepository.save(target);
                recordVersion(target, userId, now, "Adjunto del formulario inicial");
                audit(caseFile.getId(), target.getId(), userId, ACTION_UPLOAD,
                        target.getFileName() + " (contenido del formulario inicial)", now);
                touched.add(target);
            } else {
                CaseDocument doc = caseDocumentRepository.save(CaseDocument.builder()
                        .tramiteId(caseFile.getId())
                        .fileName(file.getOriginalFilename())
                        .fileType(file.getContentType())
                        .sizeBytes(file.getSize())
                        .version(1)
                        .uploadedBy(userId)
                        .uploadedAt(now)
                        .updatedAt(now)
                        .origen(SOURCE_START_FORM)
                        .origenDetalle("Formulario inicial")
                        .build());
                String path = documentStorageService.store(
                        caseFile.getId().toHexString(), doc.getId().toHexString(), 1, file);
                doc.setStoragePath(path);
                caseDocumentRepository.save(doc);
                recordVersion(doc, userId, now, "Adjunto del formulario inicial");
                audit(caseFile.getId(), doc.getId(), userId, ACTION_UPLOAD,
                        doc.getFileName() + " (formulario inicial)", now);
                touched.add(doc);
            }
        }
        if (touched.isEmpty()) {
            throw new BadRequestException("All provided files were empty");
        }
        Map<ObjectId, String> names = userNames(touched.stream().map(CaseDocument::getUploadedBy));
        return touched.stream().map(d -> caseDocumentMapper.toDto(d, names)).toList();
    }

    /** Pending-row match: exact name first, then name ignoring case. */
    private CaseDocument matchPending(List<CaseDocument> pending, MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return null;
        return pending.stream()
                .filter(d -> name.equals(d.getFileName()))
                .findFirst()
                .orElseGet(() -> pending.stream()
                        .filter(d -> d.getFileName() != null
                                && d.getFileName().equalsIgnoreCase(name))
                        .findFirst()
                        .orElse(null));
    }

    // ── Expediente completo (TAREA 7) ─────────────────────────────────────

    /**
     * Aggregates everything the "Expediente" screen needs in ONE call:
     * trámite + formulario inicial + documentos + formularios respondidos +
     * historial del workflow + historial documental + permiso efectivo.
     */
    public ExpedienteDTO getExpediente(String caseFileId, CustomUserDetails caller) {
        Procedure caseFile = findCaseOrThrow(caseFileId);
        assertCanRead(caseFile.getId(), caller);
        String id = caseFile.getId().toHexString();

        return ExpedienteDTO.builder()
                .caseFile(caseFileService.getCaseFileById(id))
                .startForm(operatorService.getCaseStartForm(id))
                .documents(listDocuments(id, caller))
                .formResponses(collectFormResponses(caseFile.getId()))
                .history(caseFileService.getCaseFileHistory(id))
                .documentAudit(getDocumentAudit(id, caller))
                .accessLevel(resolveAccessLevel(caseFile.getId(), caller))
                .build();
    }

    /** Every submitted activity form of the trámite, oldest first. */
    private List<ExpedienteFormResponseDTO> collectFormResponses(ObjectId tramiteId) {
        List<ActivityInstance> instances = activityInstanceRepository.findByTramiteId(tramiteId);
        if (instances.isEmpty()) return List.of();

        Map<ObjectId, Activity> activitiesById = activityRepository
                .findAllById(instances.stream()
                        .map(ActivityInstance::getActividadId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        List<ExpedienteFormResponseDTO> out = new ArrayList<>();
        Set<ObjectId> submitters = new HashSet<>();
        List<FormResponse> responses = new ArrayList<>();
        for (ActivityInstance instance : instances) {
            formResponseRepository.findByInstanciaActividadId(instance.getId())
                    .ifPresent(responses::add);
        }
        responses.forEach(r -> {
            if (r.getSubmittedBy() != null) submitters.add(r.getSubmittedBy());
        });
        Map<ObjectId, String> names = userNames(submitters.stream());

        Map<ObjectId, ActivityInstance> instancesById = instances.stream()
                .collect(Collectors.toMap(ActivityInstance::getId, Function.identity()));

        for (FormResponse response : responses) {
            ActivityInstance instance = instancesById.get(response.getInstanciaActividadId());
            Activity activity = instance != null && instance.getActividadId() != null
                    ? activitiesById.get(instance.getActividadId())
                    : null;
            out.add(ExpedienteFormResponseDTO.builder()
                    .activityInstanceId(response.getInstanciaActividadId() != null
                            ? response.getInstanciaActividadId().toHexString() : null)
                    .activityId(activity != null ? activity.getId().toHexString() : null)
                    .activityName(activity != null ? activity.getNombre() : null)
                    .formDefinition(activity != null
                            ? formMapper.toDefinitionDTO(activity.getFormDefinition()) : null)
                    .formData(response.getData())
                    .submittedBy(response.getSubmittedBy() != null
                            ? response.getSubmittedBy().toHexString() : null)
                    .submittedByName(names.get(response.getSubmittedBy()))
                    .submittedAt(response.getSubmittedAt())
                    .build());
        }
        out.sort((a, b) -> {
            if (a.getSubmittedAt() == null) return -1;
            if (b.getSubmittedAt() == null) return 1;
            return a.getSubmittedAt().compareTo(b.getSubmittedAt());
        });
        return out;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void audit(ObjectId tramiteId, ObjectId documentId, ObjectId userId,
                       String action, String detail, LocalDateTime when) {
        documentAuditLogRepository.save(DocumentAuditLog.builder()
                .tramiteId(tramiteId)
                .documentoId(documentId)
                .usuarioId(userId)
                .accion(action)
                .detalle(detail)
                .fecha(when)
                .build());
    }

    private Procedure findCaseOrThrow(String caseFileId) {
        ObjectId id = parseObjectId(caseFileId, "caseFileId");
        return procedureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile", caseFileId));
    }

    private CaseDocument findDocumentOrThrow(ObjectId tramiteId, String documentId) {
        ObjectId id = parseObjectId(documentId, "documentId");
        return caseDocumentRepository.findByIdAndTramiteId(id, tramiteId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private Map<ObjectId, String> userNames(Stream<ObjectId> ids) {
        List<ObjectId> distinct = ids.filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        return userRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNombre() != null ? u.getNombre() : u.getEmail()));
    }

    private String normalizeSource(String raw) {
        if (raw == null || raw.isBlank()) return SOURCE_EXPEDIENTE;
        String normalized = raw.trim().toUpperCase();
        return VALID_SOURCES.contains(normalized) ? normalized : SOURCE_EXPEDIENTE;
    }

    private ObjectId callerId(CustomUserDetails caller) {
        return caller != null && caller.getId() != null && ObjectId.isValid(caller.getId())
                ? new ObjectId(caller.getId())
                : null;
    }

    /** Caller id from the security context — for flows without a principal param. */
    private ObjectId currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails details) {
            return callerId(details);
        }
        return null;
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }

    /** Null-safe string coercion for loose start-form metadata maps. */
    private String stringOf(Object raw, String fallback) {
        if (raw == null) return fallback;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? fallback : s;
    }

    /** Null-safe numeric coercion ({@code size} arrives as Integer/Long/Double). */
    private Long longOf(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return (long) Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
