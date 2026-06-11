package com.example.backend.service;

import com.example.backend.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Almacenamiento en filesystem local (backend por defecto).
 *
 * Los bytes viven bajo la raíz configurable:
 *   {root}/{tramiteId}/{documentId}_v{version}_{nombreSanitizado}
 *
 * Una carpeta por trámite agrupa físicamente cada expediente, y el sufijo
 * de versión hace que un "Actualizar" nunca sobreescriba el binario previo
 * — las versiones viejas quedan en disco para trazabilidad aunque Mongo
 * apunte solo a la última.
 *
 * Activo cuando {@code app.documents.storage-backend=fs} (o ausente).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.documents.storage-backend", havingValue = "fs", matchIfMissing = true)
public class FilesystemDocumentStorage implements DocumentStorage {

    private final Path root;

    public FilesystemDocumentStorage(
            @Value("${app.documents.storage-path:./storage/documents}") String storagePath) {
        this.root = Paths.get(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialize document storage at " + this.root, e);
        }
        log.info("Document storage backend: filesystem (root: {})", this.root);
    }

    @Override
    public String store(String tramiteId, String documentId, int version, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File content is required");
        }
        String key = DocumentStorage.buildKey(
                tramiteId, documentId, version, DocumentStorage.sanitize(file.getOriginalFilename()));
        Path target = root.resolve(key);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            log.error("Failed to store document {} for case {}", documentId, tramiteId, e);
            throw new UncheckedIOException("Failed to store document", e);
        }
        return key;
    }

    @Override
    public String storeBytes(String tramiteId, String documentId, int version,
                             String originalFileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("File content is required");
        }
        String key = DocumentStorage.buildKey(
                tramiteId, documentId, version, DocumentStorage.sanitize(originalFileName));
        Path target = root.resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("Failed to store document bytes {} for case {}", documentId, tramiteId, e);
            throw new UncheckedIOException("Failed to store document", e);
        }
        return key;
    }

    @Override
    public byte[] load(String storageKey) {
        Path resolved = resolveSafely(storageKey);
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new UncheckedIOException("Document content not readable: " + storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return false;
        try {
            return Files.exists(resolveSafely(storageKey));
        } catch (BadRequestException e) {
            return false;
        }
    }

    /** Resuelve dentro de la raíz y rechaza path traversal ("../"). */
    private Path resolveSafely(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BadRequestException("Document has no stored content");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new BadRequestException("Invalid storage path");
        }
        return resolved;
    }
}
