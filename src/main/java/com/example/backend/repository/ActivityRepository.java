package com.example.backend.repository;

import com.example.backend.model.Activity;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityRepository extends MongoRepository<Activity, ObjectId> {

    List<Activity> findByPoliticaId(ObjectId politicaId);

    List<Activity> findByCalleId(ObjectId calleId);

    List<Activity> findByPoliticaIdAndTipo(ObjectId politicaId, String tipo);
}
