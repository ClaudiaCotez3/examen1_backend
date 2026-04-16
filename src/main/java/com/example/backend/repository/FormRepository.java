package com.example.backend.repository;

import com.example.backend.model.Form;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FormRepository extends MongoRepository<Form, ObjectId> {

    Optional<Form> findByActividadId(ObjectId actividadId);
}
