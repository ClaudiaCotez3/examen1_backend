package com.example.backend.service;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.dto.CaseFileResponseDTO;
import com.example.backend.dto.ProcessHistoryResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.ActivityInstanceMapper;
import com.example.backend.mapper.CaseFileMapper;
import com.example.backend.mapper.ProcessHistoryMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.Procedure;
import com.example.backend.model.ProcedureHistory;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.ProcedureHistoryRepository;
import com.example.backend.repository.ProcedureRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CaseFileService {

    private final ProcedureRepository procedureRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ProcedureHistoryRepository procedureHistoryRepository;
    private final ActivityRepository activityRepository;

    private final CaseFileMapper caseFileMapper;
    private final ActivityInstanceMapper activityInstanceMapper;
    private final ProcessHistoryMapper processHistoryMapper;

    public CaseFileResponseDTO getCaseFileById(String id) {
        Procedure procedure = findProcedureOrThrow(id);
        return buildFullResponse(procedure);
    }

    public List<CaseFileResponseDTO> getCaseFilesByStatus(String status) {
        String estado = mapStatus(status);
        return procedureRepository.findByEstadoOrderByFechaInicioDesc(estado).stream()
                .map(this::buildFullResponse)
                .toList();
    }

    public List<CaseFileResponseDTO> getAllCaseFiles() {
        return procedureRepository.findAll().stream()
                .map(this::buildFullResponse)
                .toList();
    }

    public List<ProcessHistoryResponseDTO> getCaseFileHistory(String caseFileId) {
        ObjectId objectId = parseObjectId(caseFileId, "caseFileId");
        // Verify case file exists
        procedureRepository.findById(objectId)
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile", caseFileId));

        List<ProcedureHistory> history = procedureHistoryRepository.findByTramiteIdOrderByFechaAsc(objectId);

        // Build activity name lookup for enriched history responses
        List<ObjectId> activityIds = history.stream()
                .map(ProcedureHistory::getActividadId)
                .distinct()
                .toList();
        Map<ObjectId, Activity> activityMap = activityRepository.findAllById(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        return history.stream()
                .map(h -> processHistoryMapper.toResponse(h, activityMap.get(h.getActividadId())))
                .toList();
    }

    /** Builds a CaseFileResponseDTO including current (non-completed) activity instances. */
    private CaseFileResponseDTO buildFullResponse(Procedure procedure) {
        CaseFileResponseDTO response = caseFileMapper.toResponse(procedure);

        // Get active (non-completed) activity instances for this case file
        List<ActivityInstance> instances = activityInstanceRepository.findByTramiteId(procedure.getId());
        List<ActivityInstance> activeInstances = instances.stream()
                .filter(i -> !"finalizado".equals(i.getEstado()))
                .toList();

        // Enrich with activity names
        List<ObjectId> activityIds = activeInstances.stream()
                .map(ActivityInstance::getActividadId)
                .distinct()
                .toList();
        Map<ObjectId, Activity> activityMap = activityRepository.findAllById(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        List<ActivityInstanceResponseDTO> currentActivities = activeInstances.stream()
                .map(i -> activityInstanceMapper.toResponse(i, activityMap.get(i.getActividadId())))
                .toList();
        response.setCurrentActivities(currentActivities);

        return response;
    }

    private Procedure findProcedureOrThrow(String id) {
        ObjectId objectId = parseObjectId(id, "id");
        return procedureRepository.findById(objectId)
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile", id));
    }

    private String mapStatus(String status) {
        if (status == null) {
            throw new BadRequestException("Status is required");
        }
        return switch (status.toUpperCase()) {
            case "ACTIVE" -> "activo";
            case "COMPLETED" -> "finalizado";
            default -> throw new BadRequestException("Invalid status: " + status + ". Allowed: ACTIVE, COMPLETED");
        };
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
