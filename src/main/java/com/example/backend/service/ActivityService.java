package com.example.backend.service;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.ActivityResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.ActivityMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.Lane;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.LaneRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private static final Set<String> VALID_TYPES = Set.of("START", "TASK", "DECISION", "PARALLEL", "END");

    private final ActivityRepository activityRepository;
    private final LaneRepository laneRepository;
    private final BusinessPolicyRepository policyRepository;
    private final ActivityMapper activityMapper;

    public ActivityResponseDTO createActivity(String policyId, ActivityRequestDTO request) {
        validateType(request.getType());

        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        if (!policyRepository.existsById(policyObjectId)) {
            throw new ResourceNotFoundException("BusinessPolicy", policyId);
        }

        ObjectId laneObjectId = parseObjectId(request.getLaneRef(), "laneRef");
        Lane lane = laneRepository.findById(laneObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("Lane", request.getLaneRef()));
        if (!lane.getPoliticaId().equals(policyObjectId)) {
            throw new BadRequestException("Lane " + request.getLaneRef() + " does not belong to policy " + policyId);
        }

        Activity entity = activityMapper.toEntity(request, policyObjectId, laneObjectId);
        Activity saved = activityRepository.save(entity);
        return activityMapper.toResponse(saved);
    }

    public List<ActivityResponseDTO> getActivitiesByPolicy(String policyId) {
        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        return activityRepository.findByPoliticaId(policyObjectId).stream()
                .map(activityMapper::toResponse)
                .toList();
    }

    private void validateType(String type) {
        if (!VALID_TYPES.contains(type)) {
            throw new BadRequestException("Invalid activity type: " + type + ". Allowed: " + VALID_TYPES);
        }
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
