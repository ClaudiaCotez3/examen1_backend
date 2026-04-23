package com.example.backend.service;

import com.example.backend.dto.OperatorTaskDTO;
import com.example.backend.dto.OperatorTasksResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.OperatorMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.Lane;
import com.example.backend.model.Procedure;
import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.LaneRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operator-side queries and the atomic "take task" action.
 *
 * Visibility rules for {@link #getOperatorTasks}:
 *   - WAITING: pool-visible tasks where the caller is in
 *     {@code assignedUserIds} and {@code claimedBy} is null.
 *     Never shows tasks claimed by someone else.
 *   - IN_PROGRESS / COMPLETED: only tasks the caller has claimed
 *     ({@code claimedBy == userId}).
 *
 * Claim rule for {@link #assignActivity} (a.k.a. takeTask):
 *   - task must be WAITING
 *   - task must not be claimed yet
 *   - caller must appear in the eligible pool
 *   The check is a single find-and-modify so concurrent clicks resolve to
 *   exactly one winner; the loser gets a 400 and refreshes the Kanban.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorService {

    private static final String STATE_WAITING = "en_espera";
    private static final String STATE_IN_PROGRESS = "en_proceso";
    private static final String STATE_COMPLETED = "finalizado";

    private final MongoTemplate mongoTemplate;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ActivityRepository activityRepository;
    private final LaneRepository laneRepository;
    private final ProcedureRepository procedureRepository;

    private final OperatorMapper operatorMapper;

    // ── Main query: grouped tasks for the Kanban ──────────────────────────

    /**
     * Grouped tasks for the 3-column Kanban.
     *
     * When a {@code userId} is supplied (the common case from the frontend),
     * the query applies the pool-visibility rules. Without a user, the
     * service falls back to the admin/supervisor filtering path (by role or
     * lane) used by the monitoring UIs.
     */
    public OperatorTasksResponseDTO getOperatorTasks(String userId, String role, String laneId) {
        if (userId != null && !userId.isBlank()) {
            return getTasksForUser(parseObjectId(userId, "userId"));
        }
        return getTasksByAdminFilters(role, laneId);
    }

    /**
     * Per-operator view:
     *   WAITING     → userId in pool AND claimedBy is null
     *   IN_PROGRESS → claimedBy = userId
     *   COMPLETED   → claimedBy = userId
     */
    private OperatorTasksResponseDTO getTasksForUser(ObjectId userId) {
        Query waitingQuery = new Query(new Criteria().andOperator(
                Criteria.where("estado").is(STATE_WAITING),
                Criteria.where("usuarios_asignados").is(userId),
                new Criteria().orOperator(
                        Criteria.where("asignado_a").is(null),
                        Criteria.where("asignado_a").exists(false)
                )
        ));
        Query inProgressQuery = new Query(new Criteria().andOperator(
                Criteria.where("estado").is(STATE_IN_PROGRESS),
                Criteria.where("asignado_a").is(userId)
        ));
        Query completedQuery = new Query(new Criteria().andOperator(
                Criteria.where("estado").is(STATE_COMPLETED),
                Criteria.where("asignado_a").is(userId)
        ));

        List<ActivityInstance> waiting = mongoTemplate.find(waitingQuery, ActivityInstance.class);
        List<ActivityInstance> inProgress = mongoTemplate.find(inProgressQuery, ActivityInstance.class);
        List<ActivityInstance> completed = mongoTemplate.find(completedQuery, ActivityInstance.class);

        List<ActivityInstance> all = new ArrayList<>(waiting.size() + inProgress.size() + completed.size());
        all.addAll(waiting);
        all.addAll(inProgress);
        all.addAll(completed);

        Lookups lookups = buildLookups(all);

        return OperatorTasksResponseDTO.builder()
                .waiting(map(waiting, lookups))
                .inProgress(map(inProgress, lookups))
                .completed(map(completed, lookups))
                .build();
    }

    /**
     * Admin/supervisor view, used when no {@code userId} is supplied. Keeps
     * the old behavior of filtering by role or lane across all instances
     * regardless of pool/claim state.
     */
    private OperatorTasksResponseDTO getTasksByAdminFilters(String role, String laneId) {
        Query query = buildAdminQuery(role, laneId);
        List<ActivityInstance> instances = mongoTemplate.find(query, ActivityInstance.class);
        if (instances.isEmpty()) {
            return OperatorTasksResponseDTO.builder()
                    .waiting(List.of()).inProgress(List.of()).completed(List.of())
                    .build();
        }

        Lookups lookups = buildLookups(instances);

        List<OperatorTaskDTO> waiting = new ArrayList<>();
        List<OperatorTaskDTO> inProgress = new ArrayList<>();
        List<OperatorTaskDTO> completed = new ArrayList<>();

        for (ActivityInstance inst : instances) {
            OperatorTaskDTO dto = toDto(inst, lookups);
            switch (inst.getEstado()) {
                case STATE_WAITING -> waiting.add(dto);
                case STATE_IN_PROGRESS -> inProgress.add(dto);
                case STATE_COMPLETED -> completed.add(dto);
                default -> log.warn("Unknown activity instance state: {}", inst.getEstado());
            }
        }

        return OperatorTasksResponseDTO.builder()
                .waiting(waiting).inProgress(inProgress).completed(completed)
                .build();
    }

    // ── Take task (atomic claim) ──────────────────────────────────────────

    /**
     * Claims a WAITING task for a user. Equivalent to the "Tomar" action in
     * the Kanban.
     *
     * Atomicity: a single find-and-modify filters on state + pool + unclaimed,
     * then stamps claimedBy + flips to IN_PROGRESS + sets startedAt in the
     * same write. Concurrent callers therefore resolve to exactly one winner.
     *
     * Failure modes (all mapped to 400):
     *   - instance does not exist
     *   - task is not WAITING anymore
     *   - task was already claimed
     *   - user is not in the eligible pool
     */
    public OperatorTaskDTO assignActivity(String activityInstanceId, String userId) {
        ObjectId instanceObjectId = parseObjectId(activityInstanceId, "activityInstanceId");
        ObjectId userObjectId = parseObjectId(userId, "userId");

        User user = userRepository.findById(userObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getActivo() != null && !user.getActivo()) {
            throw new BadRequestException("Cannot claim task: user is inactive");
        }

        LocalDateTime now = LocalDateTime.now();
        Query filter = new Query(Criteria.where("_id").is(instanceObjectId)
                .and("estado").is(STATE_WAITING)
                .and("usuarios_asignados").is(userObjectId)
                .orOperator(
                        Criteria.where("asignado_a").is(null),
                        Criteria.where("asignado_a").exists(false)
                ));

        Update update = new Update()
                .set("asignado_a", userObjectId)
                .set("estado", STATE_IN_PROGRESS)
                .set("fecha_inicio", now);

        ActivityInstance updated = mongoTemplate.findAndModify(
                filter, update,
                FindAndModifyOptions.options().returnNew(true),
                ActivityInstance.class
        );

        if (updated == null) {
            ActivityInstance existing = mongoTemplate.findById(instanceObjectId, ActivityInstance.class);
            if (existing == null) {
                throw new ResourceNotFoundException("ActivityInstance", activityInstanceId);
            }
            if (!STATE_WAITING.equals(existing.getEstado())) {
                throw new BadRequestException(
                        "Cannot take task: current status is '" + existing.getEstado()
                                + "'. Only WAITING tasks can be taken.");
            }
            if (existing.getClaimedBy() != null) {
                throw new BadRequestException("Task was already taken by another operator");
            }
            throw new BadRequestException("You are not eligible to take this task");
        }

        log.info("Task {} claimed by user {}", activityInstanceId, userId);
        return toDto(updated, buildLookups(List.of(updated)));
    }

    // ── Lookup batching ───────────────────────────────────────────────────

    /** Pre-loaded references consumed by {@link OperatorMapper#toDto}. */
    private record Lookups(
            Map<ObjectId, Activity> activitiesById,
            Map<ObjectId, Lane> lanesById,
            Map<ObjectId, Procedure> caseFilesById,
            Map<ObjectId, String> userNamesById
    ) {}

    private Lookups buildLookups(List<ActivityInstance> instances) {
        if (instances.isEmpty()) {
            return new Lookups(Map.of(), Map.of(), Map.of(), Map.of());
        }

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

        // Resolve claimer name so the Kanban can show "Bloqueada — por X"
        // without a second request. We only need display names for the
        // active claimers, not the whole pool (pool visibility is handled
        // entirely server-side).
        Set<ObjectId> claimers = new HashSet<>();
        for (ActivityInstance i : instances) {
            if (i.getClaimedBy() != null) claimers.add(i.getClaimedBy());
        }
        Map<ObjectId, String> userNamesById = claimers.isEmpty()
                ? Map.of()
                : userRepository.findAllById(claimers).stream()
                        .collect(Collectors.toMap(User::getId, User::getNombre));

        return new Lookups(activitiesById, lanesById, caseFilesById, userNamesById);
    }

    private List<OperatorTaskDTO> map(List<ActivityInstance> instances, Lookups lookups) {
        List<OperatorTaskDTO> out = new ArrayList<>(instances.size());
        for (ActivityInstance inst : instances) {
            out.add(toDto(inst, lookups));
        }
        return out;
    }

    private OperatorTaskDTO toDto(ActivityInstance inst, Lookups lookups) {
        Activity activity = lookups.activitiesById().get(inst.getActividadId());
        Lane lane = activity != null ? lookups.lanesById().get(activity.getCalleId()) : null;
        Procedure caseFile = lookups.caseFilesById().get(inst.getTramiteId());
        String claimerName = inst.getClaimedBy() != null
                ? lookups.userNamesById().get(inst.getClaimedBy())
                : null;
        return operatorMapper.toDto(inst, activity, lane, caseFile, claimerName);
    }

    // ── Admin/supervisor dynamic Query (no userId supplied) ───────────────

    private Query buildAdminQuery(String role, String laneId) {
        List<Criteria> andClauses = new ArrayList<>();

        if (role != null && !role.isBlank()) {
            List<ObjectId> userIds = resolveUserIdsByRole(role);
            if (userIds.isEmpty()) {
                return new Query(Criteria.where("_id").is(new ObjectId("000000000000000000000000")));
            }
            andClauses.add(Criteria.where("asignado_a").in(userIds));
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
        query.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "fecha_inicio"));
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

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
