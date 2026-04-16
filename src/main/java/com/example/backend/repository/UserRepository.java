package com.example.backend.repository;

import com.example.backend.model.User;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, ObjectId> {

    Optional<User> findByEmail(String email);

    List<User> findByRolIdAndActivoTrue(ObjectId rolId);

    boolean existsByEmail(String email);
}
