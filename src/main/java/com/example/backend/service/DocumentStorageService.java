package com.example.backend.service;

import com.example.backend.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Gestión Documental — filesystem storage for expediente binaries.
 *
 * MongoDB keeps metadata only ({@code documentos.ruta_almacenamiento});
 * the bytes live under the configurable root:
 *
 *   {root}/{tramiteId}/{documentId}_v{version}_{sanitizedName}
 *
 * One folder per trámite keeps each expediente physically grouped, and the
 * version suffix means an "Actualizar" never overwrites the previous binary
 * — older versions stay on disk for traceability even though Mongo only
 * points at the latest one.
 */
@Slf4j
@Service
public class DocumentStorageService {

    private final Path root;

    public DocumentStorageService(
            @Value("${app.documents.storage-path:./storage/documents}") String storagePath) {
        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize document storage at " + this.root, e);
        }
        log.info("Document storage root: {}", this.root);
    }

    /**
     * Persists an uploaded binary and returns the RELATIVE path to record on
     * the {@code CaseDocument}. Never returns absolute paths so the storage
     * root can move (env var) without a data migration.
     */
    public String store(String tramiteId, String documentId, int version, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File content is required");
        }
        String safeName = sanitize(file.getOriginalFilename());
        Path caseDir = root.resolve(tramiteId);
        Path target = caseDir.resolve(documentId + "_v" + version + "_" + safeName);
        try {
            Files.createDirectories(caseDir);
            file.transferTo(target);
        } catch (IOException e) {
            log.error("Failed to store document {} for case {}", documentId, tramiteId, e);
            throw new UncheckedIOException("Failed to store document", e);
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    /**
     * Variante para integraciones server-to-server (OnlyOffice callback):
     * persiste bytes ya descargados en lugar de un MultipartFile. Misma
     * convención de rutas/versionado que {@link #store}.
     */
    public String storeBytes(String tramiteId, String documentId, int version,
                             String originalFileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("File content is required");
        }
        String safeName = sanitize(originalFileName);
        Path caseDir = root.resolve(tramiteId);
        Path target = caseDir.resolve(documentId + "_v" + version + "_" + safeName);
        try {
            Files.createDirectories(caseDir);
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Failed to store document bytes {} for case {}", documentId, tramiteId, e);
            throw new UncheckedIOException("Failed to store document", e);
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    /** Loads a previously stored binary by its relative path. */
    public byte[] load(String relativePath) {
        Path resolved = resolveSafely(relativePath);
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException("Document content not readable: " + relativePath, e);
        }
    }

    public boolean exists(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return false;
        try {
            return Files.exists(resolveSafely(relativePath));
        } catch (BadRequestException e) {
            return false;
        }
    }

    /** Resolves inside the root and refuses path traversal ("../"). */
    private Path resolveSafely(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BadRequestException("Document has no stored content");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new BadRequestException("Invalid storage path");
        }
        return resolved;
    }

    /** Keeps the original name readable while stripping path/odd characters. */
    private String sanitize(String original) {
        String name = (original == null || original.isBlank()) ? "documento" : original;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
