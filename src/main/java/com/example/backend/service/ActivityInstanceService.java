package com.example.backend.service;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.mapper.ActivityInstanceMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityInstanceService {

    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ActivityInstanceMapper activityInstanceMapper;

    /**
     * All instances claimed by a user that are still actionable
     * (WAITING/IN_PROGRESS). Used by monitoring tools and tests.
     * WAITING instances here are by-definition already-claimed (since
     * claimedBy=userId), not pool-visible ones — the pool view lives in
     * {@link OperatorService#getOperatorTasks}.
     */
    public List<ActivityInstanceResponseDTO> getActivitiesByUser(String userId) {
        ObjectId userObjectId = parseObjectId(userId, "userId");
        List<ActivityInstance> instances = activityInstanceRepository
                .findClaimedByAndStatusIn(userObjectId, List.of("en_espera", "en_proceso"));
        return enrichAndMap(instances);
    }

    public List<ActivityInstanceResponseDTO> getActivitiesByStatus(String status) {
        String estado = mapStatus(status);
        List<ActivityInstance> instances = activityInstanceRepository.findByEstado(estado);
        return enrichAndMap(instances);
    }

    public List<ActivityInstanceResponseDTO> getActivitiesByCaseFile(String caseFileId) {
        ObjectId caseFileObjectId = parseObjectId(caseFileId, "caseFileId");
        List<ActivityInstance> instances = activityInstanceRepository.findByTramiteId(caseFileObjectId);
        return enrichAndMap(instances);
    }

    private List<ActivityInstanceResponseDTO> enrichAndMap(List<ActivityInstance> instances) {
        List<ObjectId> activityIds = instances.stream()
                .map(ActivityInstance::getActividadId)
                .distinct()
                .toList();
        Map<ObjectId, Activity> activityMap = activityRepository.findAllById(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        return instances.stream()
                .map(i -> activityInstanceMapper.toResponse(i, activityMap.get(i.getActividadId())))
                .toList();
    }

    private String mapStatus(String status) {
        if (status == null) {
            throw new BadRequestException("Status is required");
        }
        return switch (status.toUpperCase()) {
            case "WAITING" -> "en_espera";
            case "IN_PROGRESS" -> "en_proceso";
            case "COMPLETED" -> "finalizado";
            default -> throw new BadRequestException(
                    "Invalid status: " + status + ". Allowed: WAITING, IN_PROGRESS, COMPLETED");
        };
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
