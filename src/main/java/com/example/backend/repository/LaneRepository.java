package com.example.backend.repository;

import com.example.backend.model.Lane;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LaneRepository extends MongoRepository<Lane, ObjectId> {

    List<Lane> findByPoliticaIdOrderByPosicionAsc(ObjectId politicaId);
}
