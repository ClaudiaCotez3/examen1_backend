package com.example.backend.repository;

import com.example.backend.model.CaseDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CaseDocumentRepository extends MongoRepository<CaseDocument, ObjectId> {

    /** Expediente listing — newest activity first. */
    List<CaseDocument> findByTramiteIdOrderByUpdatedAtDesc(ObjectId tramiteId);

    /** Scoped lookup: a document can only be addressed through its trámite. */
    Optional<CaseDocument> findByIdAndTramiteId(ObjectId id, ObjectId tramiteId);

    long countByTramiteId(ObjectId tramiteId);
}
