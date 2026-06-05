package com.example.backend.controller;

import com.example.backend.service.CaseDocumentService;
import com.example.backend.service.CaseDocumentService.DocumentContent;
import com.example.backend.security.OnlyOfficeJwtService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Endpoints server-to-server que invoca el OnlyOffice Document Server.
 *
 * Son permitAll en SecurityConfig porque el DS no tiene la sesión del
 * usuario: la AUTORIZACIÓN es el token firmado (secreto propio de
 * OnlyOffice) que el backend emitió al construir la configuración del
 * editor — corta vida, ligado a UN documento y UN usuario. Sin token
 * válido todo responde 401/400.
 *
 *   GET  /api/onlyoffice/download?token=…  → binario del documento
 *   POST /api/onlyoffice/callback?token=…  → notificaciones de guardado
 */
@Slf4j
@RestController
@RequestMapping("/api/onlyoffice")
@RequiredArgsConstructor
public class OnlyOfficeController {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Estados del callback que implican "documento listo para guardar". */
    private static final int STATUS_SAVE = 2;
    private static final int STATUS_FORCE_SAVE = 6;

    private final CaseDocumentService caseDocumentService;
    private final OnlyOfficeJwtService onlyOfficeJwtService;

    // ── Descarga del binario por el Document Server ───────────────────────

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam("token") String token) {
        Claims claims = verifyOrThrow(token, "download");
        DocumentContent content = caseDocumentService.getContentForOnlyOffice(
                claims.get("caseFileId", String.class),
                claims.get("documentId", String.class),
                objectIdOrNull(claims.get("userId", String.class)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.builder("attachment")
                .filename(content.fileName() != null ? content.fileName() : "documento",
                        StandardCharsets.UTF_8)
                .build());
        headers.set(HttpHeaders.CONTENT_TYPE, content.fileType());
        return ResponseEntity.ok().headers(headers).body(content.bytes());
    }

    // ── Callback de guardado del Document Server ──────────────────────────

    /**
     * El DS notifica aquí los cambios de estado de la sesión de edición.
     * En status 2 (todos cerraron, hay cambios) o 6 (force save) descarga
     * el archivo final desde la URL temporal del DS y lo persiste como
     * NUEVA VERSIÓN con el versionado + auditoría existentes.
     *
     * El contrato del DS exige responder SIEMPRE {"error": 0}; cualquier
     * otra cosa hace que el editor muestre un error de guardado.
     */
    @PostMapping("/callback")
    public Map<String, Object> callback(
            @RequestParam("token") String token,
            @RequestBody Map<String, Object> body) {
        Claims claims;
        try {
            claims = verifyOrThrow(token, "callback");
        } catch (Exception ex) {
            log.warn("OnlyOffice callback con token inválido: {}", ex.getMessage());
            return Map.of("error", 1);
        }

        // Defensa en profundidad: si el DS adjunta su JWT en el body
        // (JWT_IN_BODY=true), verifícalo también.
        Object bodyToken = body.get("token");
        if (bodyToken instanceof String bt && !bt.isBlank()
                && !onlyOfficeJwtService.isValid(bt)) {
            log.warn("OnlyOffice callback con body-token inválido");
            return Map.of("error", 1);
        }

        int status = body.get("status") instanceof Number n ? n.intValue() : -1;
        if (status != STATUS_SAVE && status != STATUS_FORCE_SAVE) {
            // 1=editando, 4=cerrado sin cambios, etc. — solo acuse de recibo.
            return Map.of("error", 0);
        }

        try {
            String fileUrl = String.valueOf(body.get("url"));
            byte[] bytes = fetch(fileUrl);
            ObjectId editor = resolveEditor(body, claims);
            caseDocumentService.saveVersionFromOnlyOffice(
                    claims.get("caseFileId", String.class),
                    claims.get("documentId", String.class),
                    bytes,
                    editor);
            return Map.of("error", 0);
        } catch (Exception ex) {
            log.error("OnlyOffice callback save failed", ex);
            return Map.of("error", 1);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private Claims verifyOrThrow(String token, String expectedType) {
        Claims claims = onlyOfficeJwtService.verify(token);
        if (!expectedType.equals(claims.get("type", String.class))) {
            throw new IllegalArgumentException("Token de tipo incorrecto");
        }
        return claims;
    }

    /**
     * Usuario al que se atribuye la edición: el último editor reportado por
     * el DS (lista {@code users}); si no viene, el usuario que abrió el
     * editor (claim del token).
     */
    private ObjectId resolveEditor(Map<String, Object> body, Claims claims) {
        if (body.get("users") instanceof List<?> users && !users.isEmpty()) {
            ObjectId fromBody = objectIdOrNull(String.valueOf(users.get(0)));
            if (fromBody != null) return fromBody;
        }
        return objectIdOrNull(claims.get("userId", String.class));
    }

    private byte[] fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "DS file download returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private ObjectId objectIdOrNull(String raw) {
        return raw != null && ObjectId.isValid(raw) ? new ObjectId(raw) : null;
    }
}
