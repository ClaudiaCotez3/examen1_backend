package com.example.backend.repository;

import com.example.backend.model.FormResponse;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FormResponseRepository extends MongoRepository<FormResponse, ObjectId> {

    List<FormResponse> findByInstanciaActividadId(ObjectId instanciaActividadId);

    List<FormResponse> findByCampoId(ObjectId campoId);
}
