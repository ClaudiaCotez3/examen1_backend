package com.example.backend.repository;

import com.example.backend.model.ActivityInstance;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityInstanceRepository extends MongoRepository<ActivityInstance, ObjectId> {

    List<ActivityInstance> findByAsignadoAAndEstadoIn(ObjectId asignadoA, List<String> estados);

    List<ActivityInstance> findByTramiteId(ObjectId tramiteId);

    List<ActivityInstance> findByTramiteIdAndEstado(ObjectId tramiteId, String estado);

    List<ActivityInstance> findByActividadIdAndEstado(ObjectId actividadId, String estado);
}
