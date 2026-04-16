package com.example.backend.repository;

import com.example.backend.model.ProcedureHistory;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProcedureHistoryRepository extends MongoRepository<ProcedureHistory, ObjectId> {

    List<ProcedureHistory> findByTramiteIdOrderByFechaAsc(ObjectId tramiteId);

    List<ProcedureHistory> findByUsuarioIdOrderByFechaDesc(ObjectId usuarioId);
}
