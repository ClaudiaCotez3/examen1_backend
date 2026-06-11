package com.example.backend.service;

import com.example.backend.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Almacenamiento en AWS S3.
 *
 * La CLAVE de almacenamiento es directamente el object key:
 *   {tramiteId}/{documentId}_v{version}_{nombreSanitizado}
 *
 * — idéntica a la ruta relativa que usaba el backend filesystem, por lo
 * que las referencias ya guardadas en Mongo siguen siendo válidas tras
 * copiar los archivos al bucket (sin migración de base de datos).
 *
 * Las descargas siguen pasando por el backend ({@code /view},
 * {@code /download}), que lee los bytes con {@link #load}; el bucket puede
 * (y debe) permanecer PRIVADO — no se exponen URLs de S3 al cliente.
 *
 * Activo cuando {@code app.documents.storage-backend=s3}.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.documents.storage-backend", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {

    private final S3Client s3;
    private final String bucket;

    public S3DocumentStorage(S3Client s3,
                             @Value("${app.documents.s3.bucket:}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "app.documents.s3.bucket es obligatorio con storage-backend=s3 "
                            + "(define la variable de entorno DOCUMENTS_S3_BUCKET).");
        }
        this.s3 = s3;
        this.bucket = bucket;
        log.info("Document storage backend: S3 (bucket: {})", bucket);
    }

    @Override
    public String store(String tramiteId, String documentId, int version, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File content is required");
        }
        String key = DocumentStorage.buildKey(
                tramiteId, documentId, version, DocumentStorage.sanitize(file.getOriginalFilename()));
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key);
        if (file.getContentType() != null && !file.getContentType().isBlank()) {
            req.contentType(file.getContentType());
        }
        try {
            s3.putObject(req.build(), RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
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
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public byte[] load(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BadRequestException("Document has no stored content");
        }
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(storageKey).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new UncheckedIOException(
                    new IOException("Document content not readable: " + storageKey, e));
        }
    }

    @Override
    public boolean exists(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return false;
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }
}
