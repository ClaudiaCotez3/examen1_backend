package com.example.backend.repository;

import com.example.backend.model.Customer;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends MongoRepository<Customer, ObjectId> {

    /** Email is the primary identity key — matched case-insensitively. */
    Optional<Customer> findFirstByEmailIgnoreCase(String email);

    /** CI is the fallback key for cases captured without email. */
    Optional<Customer> findFirstByCi(String ci);

    List<Customer> findAllByOrderByNombreAsc();
}
