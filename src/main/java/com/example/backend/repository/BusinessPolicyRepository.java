package com.example.backend.repository;

import com.example.backend.model.BusinessPolicy;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BusinessPolicyRepository extends MongoRepository<BusinessPolicy, ObjectId> {

    List<BusinessPolicy> findByEstadoOrderByFechaCreacionDesc(String estado);

    List<BusinessPolicy> findByNombreContainingIgnoreCase(String nombre);
}
