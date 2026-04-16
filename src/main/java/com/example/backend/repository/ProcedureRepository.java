package com.example.backend.repository;

import com.example.backend.model.Procedure;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProcedureRepository extends MongoRepository<Procedure, ObjectId> {

    Optional<Procedure> findByCodigo(String codigo);

    List<Procedure> findByEstadoOrderByFechaInicioDesc(String estado);

    List<Procedure> findByVersionPoliticaIdAndEstado(ObjectId versionPoliticaId, String estado);
}
