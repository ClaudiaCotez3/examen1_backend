package com.example.backend.repository;

import com.example.backend.model.ActivityInstance;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityInstanceRepository extends MongoRepository<ActivityInstance, ObjectId> {

    /**
     * All instances already claimed by the given user, regardless of state.
     * Used to show "my in-progress" and "my completed" buckets.
     *
     * The Mongo field for the claimer is {@code asignado_a} (persisted name
     * kept for back-compat with existing documents); the Java accessor is
     * {@link ActivityInstance#getClaimedBy()}.
     */
    @Query("{ 'asignado_a': ?0, 'estado': { $in: ?1 } }")
    List<ActivityInstance> findClaimedByAndStatusIn(ObjectId claimedBy, List<String> estados);

    /**
     * WAITING instances visible to an operator:
     *   - still in WAITING state
     *   - not yet claimed by anyone
     *   - the operator appears in the eligible pool
     *
     * This is the "pool" query the Kanban uses for the "En espera" column.
     */
    @Query("{ 'estado': ?1, 'asignado_a': null, 'usuarios_asignados': ?0 }")
    List<ActivityInstance> findUnclaimedInPool(ObjectId userId, String estadoEnEspera);

    List<ActivityInstance> findByTramiteId(ObjectId tramiteId);

    List<ActivityInstance> findByTramiteIdAndEstado(ObjectId tramiteId, String estado);

    List<ActivityInstance> findByActividadIdAndEstado(ObjectId actividadId, String estado);

    List<ActivityInstance> findByEstado(String estado);
}
