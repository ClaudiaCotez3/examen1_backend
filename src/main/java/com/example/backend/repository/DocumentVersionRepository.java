package com.example.backend.repository;

import com.example.backend.model.DocumentVersion;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentVersionRepository extends MongoRepository<DocumentVersion, ObjectId> {

    /** Historial de un documento, versión más reciente primero. */
    List<DocumentVersion> findByDocumentoIdOrderByVersionDesc(ObjectId documentoId);

    Optional<DocumentVersion> findByDocumentoIdAndVersion(ObjectId documentoId, Integer version);

    boolean existsByDocumentoId(ObjectId documentoId);
}
