package com.example.backend.repository;

import com.example.backend.model.AiLog;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiLogRepository extends MongoRepository<AiLog, ObjectId> {

    List<AiLog> findByTramiteIdOrderByFechaDesc(ObjectId tramiteId);

    List<AiLog> findByTipoOrderByFechaDesc(String tipo);
}
