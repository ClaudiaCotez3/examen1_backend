package com.example.backend.repository;

import com.example.backend.model.FormField;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FormFieldRepository extends MongoRepository<FormField, ObjectId> {

    List<FormField> findByFormularioId(ObjectId formularioId);
}
