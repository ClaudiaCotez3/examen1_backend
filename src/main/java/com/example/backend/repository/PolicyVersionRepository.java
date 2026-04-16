package com.example.backend.repository;

import com.example.backend.model.PolicyVersion;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyVersionRepository extends MongoRepository<PolicyVersion, ObjectId> {

    List<PolicyVersion> findByPoliticaIdOrderByNumeroVersionDesc(ObjectId politicaId);

    Optional<PolicyVersion> findByPoliticaIdAndEstado(ObjectId politicaId, String estado);
}
