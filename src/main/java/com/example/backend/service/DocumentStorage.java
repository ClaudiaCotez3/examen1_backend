package com.example.backend.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Gestión Documental — abstracción de almacenamiento de binarios del
 * expediente. MongoDB guarda solo metadatos + la CLAVE de almacenamiento
 * ({@code documentos.ruta_almacenamiento}); los bytes viven en el backend
 * activo (filesystem o S3, según {@code app.documents.storage-backend}).
 *
 * La clave tiene el formato estable:
 *   {tramiteId}/{documentId}_v{version}_{nombreSanitizado}
 *
 * que sirve idéntico como ruta relativa en disco o como object key en S3,
 * por lo que cambiar de backend NO requiere migrar la base de datos.
 *
 * Implementaciones (seleccionadas por {@code @ConditionalOnProperty}):
 *   - {@link FilesystemDocumentStorage}  (default, {@code storage-backend=fs})
 *   - {@link S3DocumentStorage}          ({@code storage-backend=s3})
 */
public interface DocumentStorage {

    /**
     * Persiste un binario subido y devuelve la CLAVE de almacenamiento a
     * registrar en el {@code CaseDocument}. Nunca devuelve rutas absolutas
     * ni URLs: la clave es portable entre backends.
     */
    String store(String tramiteId, String documentId, int version, MultipartFile file);

    /**
     * Variante para integraciones server-to-server (callback de OnlyOffice):
     * persiste bytes ya descargados. Misma convención de clave/versionado.
     */
    String storeBytes(String tramiteId, String documentId, int version,
                      String originalFileName, byte[] bytes);

    /** Carga un binario previamente almacenado por su clave. */
    byte[] load(String storageKey);

    /** True si existe contenido para la clave dada. */
    boolean exists(String storageKey);

    // ── Helpers compartidos por las implementaciones ─────────────────────

    /**
     * Construye la clave estable del binario. El formato debe permanecer
     * igual entre backends para no romper las referencias ya guardadas en
     * Mongo (la migración de datos existentes solo copia archivos, sin
     * tocar la base).
     */
    static String buildKey(String tramiteId, String documentId, int version, String safeName) {
        return tramiteId + "/" + documentId + "_v" + version + "_" + safeName;
    }

    /** Conserva el nombre legible pero elimina rutas y caracteres raros. */
    static String sanitize(String original) {
        String name = (original == null || original.isBlank()) ? "documento" : original;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
