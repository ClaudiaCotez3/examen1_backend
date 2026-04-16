package com.example.backend.repository;

import com.example.backend.model.Kpi;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KpiRepository extends MongoRepository<Kpi, ObjectId> {

    List<Kpi> findByPoliticaId(ObjectId politicaId);

    Optional<Kpi> findByPoliticaIdAndActividadId(ObjectId politicaId, ObjectId actividadId);
}
