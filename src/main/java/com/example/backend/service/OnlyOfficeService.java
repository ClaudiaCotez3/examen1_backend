package com.example.backend.service;

import com.example.backend.exception.BadRequestException;
import com.example.backend.model.CaseDocument;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.OnlyOfficeJwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Integración OnlyOffice Document Server — construcción de la configuración
 * del editor (DocsAPI.DocEditor) para un documento del expediente.
 *
 * Decisiones clave:
 *   - {@code document.key} cambia con cada versión guardada (docId + vN +
 *     timestamp) → todos los co-editores de la MISMA versión comparten sala
 *     de co-edición real del DS; tras guardar, la siguiente apertura carga
 *     la versión nueva.
 *   - El DS corre en Docker: descarga el binario y notifica guardados
 *     contra {@code app.onlyoffice.internal-base-url}
 *     (host.docker.internal) usando tokens firmados de propósito único —
 *     nunca el JWT de usuarios.
 *   - El modo (edit/view) deriva de los permisos documentales existentes
 *     (READER/EDITOR por actividad) — no se crean permisos nuevos.
 */
@Service
@RequiredArgsConstructor
public class OnlyOfficeService {

    /** Formatos con edición completa en el Document Server. */
    private static final Set<String> EDITABLE_EXTENSIONS = Set.of("docx", "xlsx", "pptx");
    /** Formatos adicionales que el DS puede ABRIR en modo lectura. */
    private static final Set<String> VIEWABLE_EXTENSIONS =
            Set.of("doc", "xls", "ppt", "odt", "ods", "odp", "rtf", "pdf", "csv", "txt");

    private static final long DOWNLOAD_TOKEN_TTL_MS = 10 * 60 * 1000L;        // 10 min
    private static final long CALLBACK_TOKEN_TTL_MS = 24 * 60 * 60 * 1000L;   // 24 h

    private final CaseDocumentService caseDocumentService;
    private final OnlyOfficeJwtService onlyOfficeJwtService;

    @Value("${app.onlyoffice.url}")
    private String documentServerUrl;

    @Value("${app.onlyoffice.internal-base-url}")
    private String internalBaseUrl;

    /** ¿El formato se edita con OnlyOffice? (gating que usa el frontend). */
    public static boolean isOnlyOfficeEditable(String fileName) {
        return EDITABLE_EXTENSIONS.contains(extensionOf(fileName));
    }

    /**
     * Configuración completa para DocsAPI.DocEditor, firmada con el secreto
     * propio de OnlyOffice. Lanza 400 para formatos que el DS no maneja.
     */
    public Map<String, Object> buildEditorConfig(
            String caseFileId, String documentId, CustomUserDetails caller) {
        CaseDocument doc = caseDocumentService.requireDocument(caseFileId, documentId);
        String ext = extensionOf(doc.getFileName());
        boolean editable = EDITABLE_EXTENSIONS.contains(ext);
        if (!editable && !VIEWABLE_EXTENSIONS.contains(ext)) {
            throw new BadRequestException(
                    "El formato ." + ext + " no es compatible con OnlyOffice");
        }

        String access = caseDocumentService.resolveAccessLevel(doc.getTramiteId(), caller);
        boolean canEdit = editable && CaseDocumentService.ACCESS_EDITOR.equals(access);
        String userId = caller != null && caller.getId() != null ? caller.getId() : "anon";
        String userName = caller != null && caller.getFullName() != null
                ? caller.getFullName() : "Usuario";

        // Tokens server-to-server: el DS los presenta de vuelta al backend.
        String downloadToken = onlyOfficeJwtService.sign(Map.of(
                "type", "download",
                "caseFileId", caseFileId,
                "documentId", documentId,
                "userId", userId
        ), DOWNLOAD_TOKEN_TTL_MS);
        String callbackToken = onlyOfficeJwtService.sign(Map.of(
                "type", "callback",
                "caseFileId", caseFileId,
                "documentId", documentId,
                "userId", userId
        ), CALLBACK_TOKEN_TTL_MS);

        String downloadUrl = internalBaseUrl + "/api/onlyoffice/download?token="
                + urlEncode(downloadToken);
        String callbackUrl = internalBaseUrl + "/api/onlyoffice/callback?token="
                + urlEncode(callbackToken);

        Map<String, Object> permissions = new HashMap<>();
        permissions.put("edit", canEdit);
        permissions.put("download", true);
        permissions.put("print", true);
        permissions.put("review", canEdit);
        permissions.put("comment", canEdit);

        Map<String, Object> document = new HashMap<>();
        document.put("fileType", ext);
        document.put("key", buildKey(doc));
        document.put("title", doc.getFileName());
        document.put("url", downloadUrl);
        document.put("permissions", permissions);

        Map<String, Object> editorConfig = new HashMap<>();
        editorConfig.put("mode", canEdit ? "edit" : "view");
        editorConfig.put("lang", "es");
        editorConfig.put("callbackUrl", callbackUrl);
        editorConfig.put("user", Map.of("id", userId, "name", userName));
        editorConfig.put("customization", Map.of(
                "autosave", true,
                "forcesave", false,
                "compactHeader", true
        ));

        Map<String, Object> config = new HashMap<>();
        config.put("type", "desktop");
        config.put("documentType", documentTypeOf(ext));
        config.put("document", document);
        config.put("editorConfig", editorConfig);
        // El DS exige la config firmada cuando JWT_ENABLED=true.
        config.put("token", onlyOfficeJwtService.sign(config, CALLBACK_TOKEN_TTL_MS));

        return Map.of(
                "documentServerUrl", documentServerUrl,
                "config", config
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Clave de documento del DS: identifica la sala de co-edición y el
     * cache. Debe ser estable por versión y cambiar al guardarse una nueva
     * (charset [0-9a-zA-Z._-], máx 128).
     */
    private String buildKey(CaseDocument doc) {
        long stamp = doc.getUpdatedAt() != null
                ? doc.getUpdatedAt().atZone(ZoneId.systemDefault()).toEpochSecond()
                : 0L;
        int version = doc.getVersion() != null ? doc.getVersion() : 1;
        return doc.getId().toHexString() + "_v" + version + "_" + stamp;
    }

    private static String extensionOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    /** word | cell | slide | pdf — familia de editor del DS. */
    private static String documentTypeOf(String ext) {
        return switch (ext) {
            case "xlsx", "xls", "ods", "csv" -> "cell";
            case "pptx", "ppt", "odp" -> "slide";
            case "pdf" -> "pdf";
            default -> "word";
        };
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
