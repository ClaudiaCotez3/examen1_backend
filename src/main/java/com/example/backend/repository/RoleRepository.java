package com.example.backend.repository;

import com.example.backend.model.Role;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends MongoRepository<Role, ObjectId> {

    Optional<Role> findByNombre(String nombre);
}
