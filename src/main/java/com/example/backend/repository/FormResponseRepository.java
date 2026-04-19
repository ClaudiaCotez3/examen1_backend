package com.example.backend.repository;

import com.example.backend.model.FormResponse;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FormResponseRepository extends MongoRepository<FormResponse, ObjectId> {

    /** One form submission per activity instance (enforced by unique index). */
    Optional<FormResponse> findByInstanciaActividadId(ObjectId instanciaActividadId);

    boolean existsByInstanciaActividadId(ObjectId instanciaActividadId);
}
