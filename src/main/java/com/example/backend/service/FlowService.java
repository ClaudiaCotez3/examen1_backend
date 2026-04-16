package com.example.backend.service;

import com.example.backend.dto.FlowRequestDTO;
import com.example.backend.dto.FlowResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.FlowMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.Flow;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FlowService {

    private static final Set<String> VALID_TYPES = Set.of("LINEAR", "CONDITIONAL", "PARALLEL", "LOOP");

    private final FlowRepository flowRepository;
    private final ActivityRepository activityRepository;
    private final FlowMapper flowMapper;

    public FlowResponseDTO createFlow(FlowRequestDTO request) {
        validateType(request.getType());

        ObjectId sourceId = parseObjectId(request.getSourceRef(), "sourceRef");
        ObjectId targetId = parseObjectId(request.getTargetRef(), "targetRef");
        if (sourceId.equals(targetId)) {
            throw new BadRequestException("Flow source and target must be different activities");
        }

        Activity source = activityRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity (source)", request.getSourceRef()));
        Activity target = activityRepository.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Activity (target)", request.getTargetRef()));

        if (!source.getPoliticaId().equals(target.getPoliticaId())) {
            throw new BadRequestException("Flow source and target must belong to the same policy");
        }

        Flow entity = flowMapper.toEntity(request, sourceId, targetId);
        Flow saved = flowRepository.save(entity);
        return flowMapper.toResponse(saved);
    }

    public List<FlowResponseDTO> getFlowsByPolicy(String policyId) {
        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        List<Activity> policyActivities = activityRepository.findByPoliticaId(policyObjectId);
        Set<ObjectId> activityIds = new HashSet<>();
        for (Activity a : policyActivities) {
            activityIds.add(a.getId());
        }

        List<Flow> flows = new ArrayList<>();
        for (ObjectId activityId : activityIds) {
            flows.addAll(flowRepository.findByActividadOrigenId(activityId));
        }
        return flows.stream().map(flowMapper::toResponse).toList();
    }

    private void validateType(String type) {
        if (!VALID_TYPES.contains(type)) {
            throw new BadRequestException("Invalid flow type: " + type + ". Allowed: " + VALID_TYPES);
        }
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
