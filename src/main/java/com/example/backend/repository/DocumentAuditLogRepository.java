package com.example.backend.repository;

import com.example.backend.model.DocumentAuditLog;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentAuditLogRepository extends MongoRepository<DocumentAuditLog, ObjectId> {

    /** Historial documental of a trámite — newest first. */
    List<DocumentAuditLog> findByTramiteIdOrderByFechaDesc(ObjectId tramiteId);

    List<DocumentAuditLog> findByDocumentoIdOrderByFechaDesc(ObjectId documentoId);
}
