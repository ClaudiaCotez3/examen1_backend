package com.example.backend.service;

import com.example.backend.dto.CaseStartFormDTO;
import com.example.backend.dto.OperatorTaskDTO;
import com.example.backend.dto.OperatorTasksResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.FormMapper;
import com.example.backend.mapper.OperatorMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.Lane;
import com.example.backend.model.PolicyVersion;
import com.example.backend.model.Procedure;
import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.FlowRepository;
import com.example.backend.repository.LaneRepository;
import com.example.backend.repository.PolicyVersionRepository;
import com.example.backend.repository.ProcedureRepository;
import com.example.backend.repository.RoleRepository;
import com.example.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service dedicated to the operator Task Monitor view.
 *
 * Key design choices:
 *  - Uses MongoTemplate for a single dynamic query over activity instances, applying
 *    userId/role/lane filters at the DB level (not in memory).
 *  - Batch-loads Activity / Lane / Procedure lookups once per call (avoids N+1).
 *  - {@link #assignActivity(String, String)} uses findAndModify with a conditional
 *    filter (estado = en_espera AND asignado_a = null) to make the assignment atomic
 *    — this prevents two users clicking "Start" simultaneously from both acquiring
 *    the same task.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorService {

    private static final String STATE_WAITING = "en_espera";
    private static final String STATE_IN_PROGRESS = "en_proceso";
    private static final String STATE_COMPLETED = "finalizado";
    private static final String STATE_BLOCKED = "bloqueada";
    private static final String STATE_DISCARDED = "descartada";

    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ActivityRepository activityRepository;
    private final LaneRepository laneRepository;
    private final ProcedureRepository procedureRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final BusinessPolicyRepository businessPolicyRepository;
    private final FlowRepository flowRepository;

    private final OperatorMapper operatorMapper;
    private final FormMapper formMapper;

    // ── Main query: grouped tasks for the Kanban UI ───────────────────────

    /**
     * Returns all activity instances, grouped by status (WAITING / IN_PROGRESS / COMPLETED),
     * after applying the optional filters.
     *
     * @param userId optional — ObjectId of the assigned user
     * @param role   optional — role name (resolved to its users)
     * @param laneId optional — ObjectId of the lane/department (resolved to its activities)
     */
    public OperatorTasksResponseDTO getOperatorTasks(String userId, String role, String laneId) {
        Query query = buildQuery(userId, role, laneId);
        List<ActivityInstance> instances = mongoTemplate.find(query, ActivityInstance.class);

        if (instances.isEmpty()) {
            return OperatorTasksResponseDTO.builder()
                    .waiting(List.of())
                    .inProgress(List.of())
                    .completed(List.of())
                    .build();
        }

        // Batch-load related entities in one query each
        List<ObjectId> activityIds = instances.stream()
                .map(ActivityInstance::getActividadId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<ObjectId> caseFileIds = instances.stream()
                .map(ActivityInstance::getTramiteId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Map<ObjectId, Activity> activitiesById = activityRepository.findAllById(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        List<ObjectId> laneIds = activitiesById.values().stream()
                .map(Activity::getCalleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Map<ObjectId, Lane> lanesById = laneRepository.findAllById(laneIds).stream()
                .collect(Collectors.toMap(Lane::getId, Function.identity()));

        Map<ObjectId, Procedure> caseFilesById = procedureRepository.findAllById(caseFileIds).stream()
                .collect(Collectors.toMap(Procedure::getId, Function.identity()));

        // Pre-compute the "feeds a DECISION gateway" set so the operator UI
        // knows which tasks need the Aprobar / Rechazar follow-up dialog
        // even when they have a regular form attached.
        java.util.Set<ObjectId> decisionFeederIds = new java.util.HashSet<>();
        for (Activity activity : activitiesById.values()) {
            for (var flow : flowRepository.findByActividadOrigenId(activity.getId())) {
                Activity target = flow.getActividadDestinoId() == null
                        ? null
                        : activitiesById.get(flow.getActividadDestinoId());
                // Targets we don't have in the batch lookup are still worth
                // checking: hit the repository directly so we don't miss
                // gateways from other policies (caches do not span policies).
                if (target == null && flow.getActividadDestinoId() != null) {
                    target = activityRepository.findById(flow.getActividadDestinoId()).orElse(null);
                }
                if (target != null && "DECISION".equals(target.getTipo())) {
                    decisionFeederIds.add(activity.getId());
                    break;
                }
            }
        }

        // Map to DTOs and bucket by status
        List<OperatorTaskDTO> waiting = new ArrayList<>();
        List<OperatorTaskDTO> inProgress = new ArrayList<>();
        List<OperatorTaskDTO> completed = new ArrayList<>();

        for (ActivityInstance inst : instances) {
            Activity activity = activitiesById.get(inst.getActividadId());
            Lane lane = activity != null ? lanesById.get(activity.getCalleId()) : null;
            Procedure caseFile = caseFilesById.get(inst.getTramiteId());

            OperatorTaskDTO dto = operatorMapper.toDto(inst, activity, lane, caseFile);
            dto.setRequiresDecision(activity != null
                    && decisionFeederIds.contains(activity.getId()));

            switch (inst.getEstado()) {
                // BLOCKED tasks share the WAITING column so the operator
                // sees the full pipeline; the UI gates the "Tomar" button
                // based on the BLOCKED status, not the column.
                case STATE_WAITING, STATE_BLOCKED -> waiting.add(dto);
                case STATE_IN_PROGRESS -> inProgress.add(dto);
                case STATE_COMPLETED -> completed.add(dto);
                case STATE_DISCARDED -> { /* hidden — branch was rejected */ }
                default -> log.warn("Unknown activity instance state: {}", inst.getEstado());
            }
        }

        return OperatorTasksResponseDTO.builder()
                .waiting(waiting)
                .inProgress(inProgress)
                .completed(completed)
                .build();
    }

    // ── Assign activity (atomic) ──────────────────────────────────────────

    /**
     * Assigns a WAITING activity to a user atomically.
     *
     * Prevents race conditions: if two users click "Start" at the same moment,
     * only the first findAndModify succeeds — the second one returns null
     * because the document no longer matches the filter.
     *
     * Rules enforced at the DB level:
     *  - Activity must be in WAITING state
     *  - Activity must not already be assigned to someone else
     */
    public OperatorTaskDTO assignActivity(String activityInstanceId, String userId) {
        ObjectId instanceObjectId = parseObjectId(activityInstanceId, "activityInstanceId");
        ObjectId userObjectId = parseObjectId(userId, "userId");

        // Ensure the user exists and is active
        User user = userRepository.findById(userObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getActivo() != null && !user.getActivo()) {
            throw new BadRequestException("Cannot assign task to an inactive user");
        }

        // Atomic assignment: find-and-modify only if still WAITING and unassigned
        Query filter = new Query(Criteria.where("_id").is(instanceObjectId)
                .and("estado").is(STATE_WAITING)
                .orOperator(
                        Criteria.where("asignado_a").is(null),
                        Criteria.where("asignado_a").exists(false)
                ));

        Update update = new Update().set("asignado_a", userObjectId);

        ActivityInstance updated = mongoTemplate.findAndModify(
                filter,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ActivityInstance.class
        );

        if (updated == null) {
            // Either the instance doesn't exist, is not WAITING, or was already taken
            ActivityInstance existing = mongoTemplate.findById(instanceObjectId, ActivityInstance.class);
            if (existing == null) {
                throw new ResourceNotFoundException("ActivityInstance", activityInstanceId);
            }
            if (!STATE_WAITING.equals(existing.getEstado())) {
                throw new BadRequestException(
                        "Cannot assign activity: state is '" + existing.getEstado()
                                + "'. Only WAITING activities can be assigned.");
            }
            throw new BadRequestException("Activity was already assigned to another user");
        }

        log.info("Activity {} assigned to user {}", activityInstanceId, userId);

        // Enrich for response
        Activity activity = activityRepository.findById(updated.getActividadId()).orElse(null);
        Lane lane = activity != null && activity.getCalleId() != null
                ? laneRepository.findById(activity.getCalleId()).orElse(null)
                : null;
        Procedure caseFile = procedureRepository.findById(updated.getTramiteId()).orElse(null);

        return operatorMapper.toDto(updated, activity, lane, caseFile);
    }

    // ── Internal: dynamic Query builder ───────────────────────────────────

    private Query buildQuery(String userId, String role, String laneId) {
        List<Criteria> andClauses = new ArrayList<>();

        if (userId != null && !userId.isBlank()) {
            // An operator's "my tasks" view must surface three buckets:
            //   1) tasks they already claimed (asignado_a == self),
            //   2) WAITING instances where their id was copied into the
            //      runtime pool (usuarios_asignados contains self), and
            //   3) WAITING instances belonging to an Activity definition
            //      where the user is in the pool — even if the instance
            //      itself was created before the runtime pool was populated
            //      (older trámites, fallback path).
            // Without (3) any case opened before the assignee-copy fix is
            // permanently invisible until manually claimed.
            ObjectId userObjectId = parseObjectId(userId, "userId");
            List<ObjectId> eligibleActivityIds = activityRepository
                    .findByAssignedUserId(userId).stream()
                    .map(Activity::getId)
                    .toList();

            List<Criteria> orClauses = new ArrayList<>();
            orClauses.add(Criteria.where("asignado_a").is(userObjectId));
            orClauses.add(Criteria.where("usuarios_asignados").is(userObjectId));
            if (!eligibleActivityIds.isEmpty()) {
                orClauses.add(Criteria.where("actividad_id").in(eligibleActivityIds));
            }
            andClauses.add(new Criteria().orOperator(orClauses.toArray(new Criteria[0])));
        }

        if (role != null && !role.isBlank()) {
            List<ObjectId> userIds = resolveUserIdsByRole(role);
            if (userIds.isEmpty()) {
                // No users with this role → no tasks
                return new Query(Criteria.where("_id").is(new ObjectId("000000000000000000000000")));
            }
            andClauses.add(new Criteria().orOperator(
                    Criteria.where("asignado_a").in(userIds),
                    Criteria.where("usuarios_asignados").in(userIds)
            ));
        }

        if (laneId != null && !laneId.isBlank()) {
            List<ObjectId> activityIds = resolveActivityIdsByLane(laneId);
            if (activityIds.isEmpty()) {
                return new Query(Criteria.where("_id").is(new ObjectId("000000000000000000000000")));
            }
            andClauses.add(Criteria.where("actividad_id").in(activityIds));
        }

        Query query = new Query();
        if (!andClauses.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(andClauses.toArray(new Criteria[0])));
        }
        // Sort: most recently updated first. WAITING rows have no fechaInicio
        // yet, so fall back to createdAt to keep the Kanban deterministic.
        query.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "fecha_inicio", "fecha_creacion"));
        return query;
    }

    private List<ObjectId> resolveUserIdsByRole(String roleName) {
        Role r = roleRepository.findByNombre(roleName).orElse(null);
        if (r == null) {
            log.debug("Role '{}' not found", roleName);
            return Collections.emptyList();
        }
        return userRepository.findByRolIdAndActivoTrue(r.getId()).stream()
                .map(User::getId)
                .toList();
    }

    private List<ObjectId> resolveActivityIdsByLane(String laneId) {
        ObjectId laneObjectId = parseObjectId(laneId, "lane");
        return activityRepository.findByCalleId(laneObjectId).stream()
                .map(Activity::getId)
                .toList();
    }

    /**
     * Returns the start-form snapshot of a trámite — the schema declared
     * by the policy together with the values the consultor filled in
     * before launching the case. Used by the operator's task modal to
     * surface customer info while the operator works on a task.
     */
    public CaseStartFormDTO getCaseStartForm(String caseFileId) {
        ObjectId caseId = parseObjectId(caseFileId, "caseFileId");
        Procedure procedure = procedureRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile", caseFileId));
        PolicyVersion version = policyVersionRepository
                .findById(procedure.getVersionPoliticaId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PolicyVersion", procedure.getVersionPoliticaId().toHexString()));
        BusinessPolicy policy = businessPolicyRepository.findById(version.getPoliticaId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "BusinessPolicy", version.getPoliticaId().toHexString()));

        FormDefinition definition = policy.getStartFormDefinition();
        return CaseStartFormDTO.builder()
                .definition(formMapper.toDefinitionDTO(definition))
                .data(procedure.getStartFormData())
                .build();
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
