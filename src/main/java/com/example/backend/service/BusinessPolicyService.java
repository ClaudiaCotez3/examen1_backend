package com.example.backend.service;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.ActivityResponseDTO;
import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.BusinessPolicyResponseDTO;
import com.example.backend.dto.FlowRequestDTO;
import com.example.backend.dto.FlowResponseDTO;
import com.example.backend.dto.FullSaveRequestDTO;
import com.example.backend.dto.FullSaveResponseDTO;
import com.example.backend.dto.LaneRequestDTO;
import com.example.backend.dto.LaneResponseDTO;
import com.example.backend.dto.PolicyVersionResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.ActivityMapper;
import com.example.backend.mapper.BusinessPolicyMapper;
import com.example.backend.mapper.FlowMapper;
import com.example.backend.mapper.LaneMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Flow;
import com.example.backend.model.Lane;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.FlowRepository;
import com.example.backend.repository.LaneRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BusinessPolicyService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final Set<String> ALLOWED_STATUSES = Set.of(STATUS_DRAFT, STATUS_ACTIVE);

    private static final Set<String> ACTIVITY_TYPES = Set.of("START", "TASK", "DECISION", "END");
    private static final Set<String> FLOW_TYPES = Set.of("LINEAR", "CONDITIONAL", "PARALLEL", "LOOP");
    private static final Set<String> ASSIGNMENT_TYPES = Set.of("USER", "CANDIDATE_USERS", "DEPARTMENT");

    private final BusinessPolicyRepository policyRepository;
    private final LaneRepository laneRepository;
    private final ActivityRepository activityRepository;
    private final FlowRepository flowRepository;

    private final BusinessPolicyMapper policyMapper;
    private final LaneMapper laneMapper;
    private final ActivityMapper activityMapper;
    private final FlowMapper flowMapper;

    private final BpmnXmlParser bpmnXmlParser;
    private final PolicyVersionService policyVersionService;

    public BusinessPolicyResponseDTO createPolicy(BusinessPolicyRequestDTO request) {
        BusinessPolicy entity = policyMapper.toEntity(request);
        LocalDateTime now = LocalDateTime.now();
        entity.setEstado(resolveStatusOrDefault(request.getStatus()));
        entity.setVersion(1);
        entity.setFechaCreacion(now);
        entity.setFechaActualizacion(now);
        BusinessPolicy saved = policyRepository.save(entity);
        return policyMapper.toResponse(saved);
    }

    public BusinessPolicyResponseDTO updatePolicy(String id, BusinessPolicyRequestDTO request) {
        BusinessPolicy policy = findPolicyOrThrow(id);
        policyMapper.updateEntity(policy, request);
        if (request.getStatus() != null) {
            policy.setEstado(resolveStatusOrDefault(request.getStatus()));
        }
        policy.setFechaActualizacion(LocalDateTime.now());
        BusinessPolicy saved = policyRepository.save(policy);
        return policyMapper.toResponse(saved);
    }

    public List<BusinessPolicyResponseDTO> getAllPolicies() {
        return policyRepository.findAll().stream()
                .filter(p -> !STATUS_ARCHIVED.equals(p.getEstado()))
                .map(policyMapper::toResponse)
                .toList();
    }

    public BusinessPolicyResponseDTO getPolicyById(String id) {
        BusinessPolicy policy = findPolicyOrThrow(id);
        return buildFullResponse(policy);
    }

    /** Logical delete: flips status to ARCHIVED instead of removing documents. */
    public void deletePolicy(String id) {
        BusinessPolicy policy = findPolicyOrThrow(id);
        if (STATUS_ARCHIVED.equals(policy.getEstado())) {
            throw new BadRequestException("Policy is already archived");
        }
        policy.setEstado(STATUS_ARCHIVED);
        policy.setFechaActualizacion(LocalDateTime.now());
        policyRepository.save(policy);
    }

    /**
     * Atomically persists the full policy graph (policy + lanes + activities + flows) from a single payload.
     * Client-side ids (clientId / laneRef / sourceRef / targetRef) are resolved to real Mongo ids while saving.
     * Best-effort rollback: if any step fails, previously inserted documents from this call are deleted.
     */
    public BusinessPolicyResponseDTO saveFullPolicyStructure(BusinessPolicyRequestDTO request) {
        validateGraph(request);

        BusinessPolicy policy = policyMapper.toEntity(request);
        LocalDateTime now = LocalDateTime.now();
        policy.setEstado(resolveStatusOrDefault(request.getStatus()));
        policy.setVersion(1);
        policy.setFechaCreacion(now);
        policy.setFechaActualizacion(now);
        BusinessPolicy savedPolicy = policyRepository.save(policy);

        List<Lane> savedLanes = new ArrayList<>();
        List<Activity> savedActivities = new ArrayList<>();
        List<Flow> savedFlows = new ArrayList<>();

        try {
            Map<String, ObjectId> laneIdByClientId = new HashMap<>();
            for (LaneRequestDTO laneDto : request.getLanes()) {
                Lane lane = laneMapper.toEntity(laneDto, savedPolicy.getId());
                Lane saved = laneRepository.save(lane);
                savedLanes.add(saved);
                if (laneDto.getClientId() != null) {
                    laneIdByClientId.put(laneDto.getClientId(), saved.getId());
                }
            }

            Map<String, ObjectId> activityIdByClientId = new HashMap<>();
            for (ActivityRequestDTO activityDto : request.getActivities()) {
                ObjectId laneId = laneIdByClientId.get(activityDto.getLaneRef());
                if (laneId == null) {
                    throw new BadRequestException(
                            "Activity '" + activityDto.getName() + "' references unknown lane: " + activityDto.getLaneRef());
                }
                Activity activity = activityMapper.toEntity(activityDto, savedPolicy.getId(), laneId);
                Activity saved = activityRepository.save(activity);
                savedActivities.add(saved);
                if (activityDto.getClientId() != null) {
                    activityIdByClientId.put(activityDto.getClientId(), saved.getId());
                }
            }

            for (FlowRequestDTO flowDto : request.getFlows()) {
                ObjectId sourceId = activityIdByClientId.get(flowDto.getSourceRef());
                ObjectId targetId = activityIdByClientId.get(flowDto.getTargetRef());
                if (sourceId == null) {
                    throw new BadRequestException("Flow references unknown source activity: " + flowDto.getSourceRef());
                }
                if (targetId == null) {
                    throw new BadRequestException("Flow references unknown target activity: " + flowDto.getTargetRef());
                }
                Flow flow = flowMapper.toEntity(flowDto, sourceId, targetId);
                Flow saved = flowRepository.save(flow);
                savedFlows.add(saved);
            }
        } catch (RuntimeException ex) {
            savedFlows.forEach(flowRepository::delete);
            savedActivities.forEach(activityRepository::delete);
            savedLanes.forEach(laneRepository::delete);
            policyRepository.delete(savedPolicy);
            throw ex;
        }

        // Mint a snapshot AFTER the structural save succeeds so failed
        // attempts don't leave phantom version records. Bump policy.version
        // to mirror the new snapshot number, so clients can read the active
        // version without querying the versions collection.
        PolicyVersionResponseDTO snapshot =
                policyVersionService.createSnapshot(savedPolicy.getId(), savedPolicy.getBpmnXml());
        if (snapshot != null && snapshot.getVersionNumber() != null) {
            savedPolicy.setVersion(snapshot.getVersionNumber());
            policyRepository.save(savedPolicy);
        }

        return buildFullResponse(savedPolicy);
    }

    /**
     * Single entry point for the visual designer: persists raw BPMN XML +
     * structured graph in one call, mints a version snapshot, and returns
     * a narrow ack ({@code policyId, status, versionNumber}).
     *
     * Resolution order for the structured graph:
     *   1) {@code request.structure} when provided — preferred, since the
     *      frontend already parsed the diagram and resolved catalog
     *      references (form ids, user ids).
     *   2) parser output otherwise — falls back to {@link BpmnXmlParser}
     *      so legacy callers / external integrations can post pure XML.
     */
    public FullSaveResponseDTO fullSavePolicy(FullSaveRequestDTO request) {
        BusinessPolicyRequestDTO structure = request.getStructure();
        if (structure == null) {
            structure = bpmnXmlParser.parse(request.getBpmnXml());
        }
        // The XML is the authoritative diagram representation — always
        // overwrite whatever the structure claimed with what the caller sent.
        structure.setBpmnXml(request.getBpmnXml());

        BusinessPolicyResponseDTO saved = saveFullPolicyStructure(structure);

        Integer versionNumber = policyVersionService
                .getVersionsByPolicy(saved.getId())
                .stream()
                .findFirst()
                .map(PolicyVersionResponseDTO::getVersionNumber)
                .orElse(null);

        return FullSaveResponseDTO.builder()
                .policyId(saved.getId())
                .status("saved")
                .versionNumber(versionNumber)
                .build();
    }

    private BusinessPolicyResponseDTO buildFullResponse(BusinessPolicy policy) {
        ObjectId policyId = policy.getId();

        List<LaneResponseDTO> lanes = laneRepository.findByPoliticaIdOrderByPosicionAsc(policyId).stream()
                .map(laneMapper::toResponse)
                .toList();

        List<Activity> activities = activityRepository.findByPoliticaId(policyId);
        List<ActivityResponseDTO> activityResponses = activities.stream()
                .map(activityMapper::toResponse)
                .toList();

        Set<ObjectId> activityIds = new HashSet<>();
        for (Activity a : activities) {
            activityIds.add(a.getId());
        }
        List<FlowResponseDTO> flows = new ArrayList<>();
        for (ObjectId activityId : activityIds) {
            flowRepository.findByActividadOrigenId(activityId).stream()
                    .map(flowMapper::toResponse)
                    .forEach(flows::add);
        }

        BusinessPolicyResponseDTO response = policyMapper.toResponse(policy);
        response.setLanes(lanes);
        response.setActivities(activityResponses);
        response.setFlows(flows);
        return response;
    }

    private void validateGraph(BusinessPolicyRequestDTO request) {
        List<LaneRequestDTO> lanes = request.getLanes();
        List<ActivityRequestDTO> activities = request.getActivities();
        List<FlowRequestDTO> flows = request.getFlows();

        if (lanes == null || lanes.isEmpty()) {
            throw new BadRequestException("Policy must contain at least one department (lane)");
        }
        if (activities == null || activities.isEmpty()) {
            throw new BadRequestException("Policy must contain at least one activity");
        }
        if (flows == null) {
            throw new BadRequestException("Flows list is required (may be empty only for a single-activity policy)");
        }

        Set<String> laneClientIds = new HashSet<>();
        for (LaneRequestDTO lane : lanes) {
            if (lane.getClientId() == null || lane.getClientId().isBlank()) {
                throw new BadRequestException("Each department must declare a clientId for full-policy save");
            }
            if (!laneClientIds.add(lane.getClientId())) {
                throw new BadRequestException("Duplicate department clientId: " + lane.getClientId());
            }
        }

        Set<String> activityClientIds = new HashSet<>();
        int startCount = 0;
        int endCount = 0;
        for (ActivityRequestDTO activity : activities) {
            if (activity.getClientId() == null || activity.getClientId().isBlank()) {
                throw new BadRequestException("Each activity must declare a clientId for full-policy save");
            }
            if (!activityClientIds.add(activity.getClientId())) {
                throw new BadRequestException("Duplicate activity clientId: " + activity.getClientId());
            }
            if (!ACTIVITY_TYPES.contains(activity.getType())) {
                throw new BadRequestException(
                        "Invalid activity type '" + activity.getType() + "' for '" + activity.getName() + "'");
            }
            if (!laneClientIds.contains(activity.getLaneRef())) {
                throw new BadRequestException(
                        "Activity '" + activity.getName() + "' references unknown department: " + activity.getLaneRef());
            }
            // Only TASK activities carry an assignment type; validate when one was sent.
            if ("TASK".equals(activity.getType())
                    && activity.getAssignmentType() != null
                    && !activity.getAssignmentType().isBlank()
                    && !ASSIGNMENT_TYPES.contains(activity.getAssignmentType())) {
                throw new BadRequestException(
                        "Invalid assignmentType '" + activity.getAssignmentType()
                                + "' for activity '" + activity.getName()
                                + "'. Allowed: " + ASSIGNMENT_TYPES);
            }
            if ("START".equals(activity.getType())) {
                startCount++;
            } else if ("END".equals(activity.getType())) {
                endCount++;
            }
        }

        if (startCount < 1) {
            throw new BadRequestException("Policy must have at least one START activity");
        }
        if (endCount < 1) {
            throw new BadRequestException("Policy must have at least one END activity");
        }

        Set<String> connected = new HashSet<>();
        for (FlowRequestDTO flow : flows) {
            if (!FLOW_TYPES.contains(flow.getType())) {
                throw new BadRequestException("Invalid flow type: " + flow.getType());
            }
            if (!activityClientIds.contains(flow.getSourceRef())) {
                throw new BadRequestException("Flow references unknown source activity: " + flow.getSourceRef());
            }
            if (!activityClientIds.contains(flow.getTargetRef())) {
                throw new BadRequestException("Flow references unknown target activity: " + flow.getTargetRef());
            }
            if (flow.getSourceRef().equals(flow.getTargetRef())) {
                throw new BadRequestException("Flow source and target must be different: " + flow.getSourceRef());
            }
            connected.add(flow.getSourceRef());
            connected.add(flow.getTargetRef());
        }

        if (activities.size() > 1) {
            for (ActivityRequestDTO activity : activities) {
                if (!connected.contains(activity.getClientId())) {
                    throw new BadRequestException(
                            "Orphan activity detected (no flow connected): " + activity.getName());
                }
            }
        }
    }

    private String resolveStatusOrDefault(String requested) {
        if (requested == null || requested.isBlank()) {
            return STATUS_DRAFT;
        }
        if (!ALLOWED_STATUSES.contains(requested)) {
            throw new BadRequestException("Invalid policy status: " + requested + ". Allowed: " + ALLOWED_STATUSES);
        }
        return requested;
    }

    private BusinessPolicy findPolicyOrThrow(String id) {
        ObjectId objectId = parseObjectId(id, "id");
        return policyRepository.findById(objectId)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPolicy", id));
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
