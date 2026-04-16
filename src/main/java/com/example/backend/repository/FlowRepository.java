package com.example.backend.repository;

import com.example.backend.model.Flow;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlowRepository extends MongoRepository<Flow, ObjectId> {

    List<Flow> findByActividadOrigenId(ObjectId actividadOrigenId);

    List<Flow> findByActividadDestinoId(ObjectId actividadDestinoId);
}
