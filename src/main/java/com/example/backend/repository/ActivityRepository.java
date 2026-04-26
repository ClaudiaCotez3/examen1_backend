package com.example.backend.repository;

import com.example.backend.model.Activity;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityRepository extends MongoRepository<Activity, ObjectId> {

    List<Activity> findByPoliticaId(ObjectId politicaId);

    List<Activity> findByCalleId(ObjectId calleId);

    List<Activity> findByPoliticaIdAndTipo(ObjectId politicaId, String tipo);

    /**
     * Activities where the given user appears in the eligible-operator pool.
     * The pool on the definition is stored as a hex-string array (matching
     * what the policy-designer dropdown writes), so the parameter type is
     * {@code String}, not {@code ObjectId}. Used by the operator Kanban to
     * surface tasks the user is allowed to claim even when the runtime
     * instance pre-dates the field-copy fix and has an empty pool.
     */
    @Query("{ 'usuarios_asignados': ?0 }")
    List<Activity> findByAssignedUserId(String userId);
}
