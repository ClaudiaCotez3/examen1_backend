package com.example.backend.service;

import com.example.backend.dto.LaneRequestDTO;
import com.example.backend.dto.LaneResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.LaneMapper;
import com.example.backend.model.Lane;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.LaneRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LaneService {

    private final LaneRepository laneRepository;
    private final BusinessPolicyRepository policyRepository;
    private final LaneMapper laneMapper;

    public LaneResponseDTO createLane(String policyId, LaneRequestDTO request) {
        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        if (!policyRepository.existsById(policyObjectId)) {
            throw new ResourceNotFoundException("BusinessPolicy", policyId);
        }
        Lane entity = laneMapper.toEntity(request, policyObjectId);
        Lane saved = laneRepository.save(entity);
        return laneMapper.toResponse(saved);
    }

    public List<LaneResponseDTO> getLanesByPolicy(String policyId) {
        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        return laneRepository.findByPoliticaIdOrderByPosicionAsc(policyObjectId).stream()
                .map(laneMapper::toResponse)
                .toList();
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
