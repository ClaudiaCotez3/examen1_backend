package com.example.backend.repository;

import com.example.backend.model.CustomerDevice;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerDeviceRepository extends MongoRepository<CustomerDevice, ObjectId> {

    List<CustomerDevice> findByClienteId(ObjectId clienteId);

    Optional<CustomerDevice> findFirstByFcmToken(String fcmToken);

    void deleteByFcmToken(String fcmToken);
}
