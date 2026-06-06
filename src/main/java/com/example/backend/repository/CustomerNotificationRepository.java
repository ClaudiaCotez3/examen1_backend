package com.example.backend.repository;

import com.example.backend.model.CustomerNotification;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerNotificationRepository
        extends MongoRepository<CustomerNotification, ObjectId> {

    List<CustomerNotification> findTop50ByClienteIdOrderByFechaDesc(ObjectId clienteId);

    List<CustomerNotification> findByClienteIdAndLeidaFalse(ObjectId clienteId);

    long countByClienteIdAndLeidaFalse(ObjectId clienteId);
}
