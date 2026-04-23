package com.example.backend.service;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.dto.CaseFileResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.ActivityInstanceMapper;
import com.example.backend.mapper.CaseFileMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.Flow;
import com.example.backend.model.PolicyVersion;
import com.example.backend.model.Procedure;
import com.example.backend.model.ProcedureHistory;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.FlowRepository;
import com.example.backend.repository.PolicyVersionRepository;
import com.example.backend.repository.ProcedureHistoryRepository;
import com.example.backend.repository.ProcedureRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core workflow engine.
 *
 * Responsibilities:
 *   - starting processes (creating case files from a policy version)
 *   - claiming a WAITING task atomically ({@link #startActivity})
 *   - completing a task (only the claimer may complete)
 *   - creating the next ActivityInstance(s) based on outgoing flows
 *
 * Assignment model (matches {@link ActivityInstance} javadoc):
 *   Each instance carries an eligible pool ({@code assignedUserIds}) copied
 *   from the Activity definition at creation time. {@code claimedBy} is null
 *   while the task is pool-visible and set atomically when someone takes it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngineService {

    private static final String STATUS_ACTIVE = "activo";
    private static final String STATUS_COMPLETED = "finalizado";

    private static final String ACTIVITY_WAITING = "en_espera";
    private static final String ACTIVITY_IN_PROGRESS = "en_proceso";
    private static final String ACTIVITY_COMPLETED = "finalizado";

    private static final String ACTION_STARTED = "STARTED";
    private static final String ACTION_COMPLETED = "COMPLETED";
    private static final String ACTION_TRANSITION = "TRANSITION";

    private final ProcedureRepository procedureRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final ActivityRepository activityRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final FlowRepository flowRepository;
    private final ProcedureHistoryRepository procedureHistoryRepository;
    private final MongoTemplate mongoTemplate;
    private final FormService formService;

    private final CaseFileMapper caseFileMapper;
    private final ActivityInstanceMapper activityInstanceMapper;

    // ── Start Process ──────────────────────────────────────────────────────

    /**
     * Creates a new case file (process instance) from a policy version.
     * Finds the START activity, creates the first ActivityInstance in WAITING
     * state (with its eligible pool) and records the event in process history.
     */
    public CaseFileResponseDTO startProcess(String policyVersionId) {
        ObjectId versionObjectId = parseObjectId(policyVersionId, "policyVersionId");

        PolicyVersion version = policyVersionRepository.findById(versionObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyVersion", policyVersionId));

        if (!"ACTIVE".equals(version.getEstado())) {
            throw new BadRequestException("Cannot start process: policy version is not ACTIVE");
        }

        List<Activity> startActivities = activityRepository
                .findByPoliticaIdAndTipo(version.getPoliticaId(), "START");

        if (startActivities.isEmpty()) {
            throw new BadRequestException("Policy has no START activity defined");
        }
        Activity startActivity = startActivities.get(0);

        LocalDateTime now = LocalDateTime.now();
        Procedure caseFile = Procedure.builder()
                .codigo(generateCaseCode())
                .versionPoliticaId(versionObjectId)
                .estado(STATUS_ACTIVE)
                .fechaInicio(now)
                .build();
        Procedure savedCaseFile = procedureRepository.save(caseFile);

        ActivityInstance firstInstance = buildInstance(savedCaseFile.getId(), startActivity, now);
        ActivityInstance savedInstance = activityInstanceRepository.save(firstInstance);

        saveHistory(savedCaseFile.getId(), startActivity.getId(), null, ACTION_STARTED, now);

        log.info("Process started: caseFile={}, policy={}, startActivity={}",
                savedCaseFile.getId(), version.getPoliticaId(), startActivity.getId());

        CaseFileResponseDTO response = caseFileMapper.toResponse(savedCaseFile);
        response.setCurrentActivities(List.of(
                activityInstanceMapper.toResponse(savedInstance, startActivity)));
        return response;
    }

    // ── Take / Start Task (atomic claim) ───────────────────────────────────

    /**
     * Claims a WAITING task for a user atomically. Implements the "Tomar"
     * action in the Kanban.
     *
     * Preconditions (enforced at the DB level via find-and-modify):
     *   1. instance exists
     *   2. {@code status == WAITING}
     *   3. {@code claimedBy == null}
     *   4. {@code userId} appears in {@code assignedUserIds}
     *
     * Successful transition:
     *   - {@code claimedBy = userId}
     *   - {@code status = IN_PROGRESS}
     *   - {@code startedAt = now}
     *
     * If the user is not in the eligible pool, or the task is already
     * claimed / not WAITING, throws {@link BadRequestException} — the
     * caller should refresh the Kanban.
     */
    public ActivityInstanceResponseDTO startActivity(String activityInstanceId, String userId) {
        ObjectId instanceObjectId = parseObjectId(activityInstanceId, "activityInstanceId");
        if (userId == null || userId.isBlank()) {
            // Downgrade gracefully: auto-advance paths (START / END / DECISION)
            // still call startActivity without a user id; those cases go
            // through the legacy transition below.
            return autoAdvance(instanceObjectId);
        }
        ObjectId userObjectId = parseObjectId(userId, "userId");
        LocalDateTime now = LocalDateTime.now();

        // Atomic claim: WAITING + not yet claimed + user is in the pool.
        Query filter = new Query(Criteria.where("_id").is(instanceObjectId)
                .and("estado").is(ACTIVITY_WAITING)
                .and("usuarios_asignados").is(userObjectId)
                .orOperator(
                        Criteria.where("asignado_a").is(null),
                        Criteria.where("asignado_a").exists(false)
                ));
        Update update = new Update()
                .set("estado", ACTIVITY_IN_PROGRESS)
                .set("fecha_inicio", now)
                .set("asignado_a", userObjectId);

        ActivityInstance updated = mongoTemplate.findAndModify(
                filter, update,
                FindAndModifyOptions.options().returnNew(true),
                ActivityInstance.class
        );

        if (updated == null) {
            // Work out *why* the claim failed so the operator gets a clear
            // message (not just "400 Bad Request"). This read is off the
            // hot path — it only runs on a conflict.
            ActivityInstance existing = mongoTemplate.findById(instanceObjectId, ActivityInstance.class);
            if (existing == null) {
                throw new ResourceNotFoundException("ActivityInstance", activityInstanceId);
            }
            if (!ACTIVITY_WAITING.equals(existing.getEstado())) {
                throw new BadRequestException(
                        "Cannot take task: current status is '" + existing.getEstado()
                                + "'. Only WAITING tasks can be taken.");
            }
            if (existing.getClaimedBy() != null) {
                throw new BadRequestException("Task was already taken by another operator");
            }
            // Not WAITING-vs-claimed → must be the pool check that failed.
            throw new BadRequestException("You are not eligible to take this task");
        }

        saveHistory(updated.getTramiteId(), updated.getActividadId(), userObjectId, ACTION_STARTED, now);
        log.info("Task {} claimed by user {}", activityInstanceId, userId);

        Activity activity = activityRepository.findById(updated.getActividadId()).orElse(null);
        return activityInstanceMapper.toResponse(updated, activity);
    }

    /**
     * Transition WAITING → IN_PROGRESS without claiming — used for automated
     * nodes (START / DECISION auto-advance). Never called from the Kanban.
     */
    private ActivityInstanceResponseDTO autoAdvance(ObjectId instanceObjectId) {
        LocalDateTime now = LocalDateTime.now();

        Query filter = new Query(Criteria.where("_id").is(instanceObjectId)
                .and("estado").is(ACTIVITY_WAITING));
        Update update = new Update()
                .set("estado", ACTIVITY_IN_PROGRESS)
                .set("fecha_inicio", now);

        ActivityInstance updated = mongoTemplate.findAndModify(
                filter, update,
                FindAndModifyOptions.options().returnNew(true),
                ActivityInstance.class
        );

        if (updated == null) {
            ActivityInstance existing = mongoTemplate.findById(instanceObjectId, ActivityInstance.class);
            if (existing == null) {
                throw new ResourceNotFoundException("ActivityInstance", instanceObjectId.toHexString());
            }
            throw new BadRequestException(
                    "Cannot start activity: current status is '" + existing.getEstado()
                            + "'. Only WAITING activities can be started.");
        }

        saveHistory(updated.getTramiteId(), updated.getActividadId(), null, ACTION_STARTED, now);

        Activity activity = activityRepository.findById(updated.getActividadId()).orElse(null);
        return activityInstanceMapper.toResponse(updated, activity);
    }

    // ── Complete Activity ──────────────────────────────────────────────────

    /**
     * Completes an activity instance and advances the workflow.
     *
     * Ownership rule: only the operator in {@link ActivityInstance#getClaimedBy()}
     * may complete. A caller that hasn't claimed the task gets a 400.
     *
     * Form rule: if the activity declares a form, a response must exist
     * before the task can be completed.
     */
    public CaseFileResponseDTO completeActivity(String activityInstanceId, String userId) {
        ActivityInstance instance = findInstanceOrThrow(activityInstanceId);

        // State machine — only IN_PROGRESS can transition to COMPLETED.
        if (!ACTIVITY_IN_PROGRESS.equals(instance.getEstado())) {
            throw new BadRequestException(
                    "Cannot complete task: current status is '" + instance.getEstado()
                            + "'. Only IN_PROGRESS tasks can be completed.");
        }

        // Ownership — only the claimer can complete. Internal callers (the
        // engine auto-completing END nodes) pass userId=null and are allowed.
        ObjectId userObjectId = (userId != null && !userId.isBlank())
                ? parseObjectId(userId, "userId")
                : null;
        if (userObjectId != null) {
            if (instance.getClaimedBy() == null) {
                throw new BadRequestException("Cannot complete task: it has not been claimed yet");
            }
            if (!instance.getClaimedBy().equals(userObjectId)) {
                throw new BadRequestException("Only the operator who claimed this task can complete it");
            }
        }

        // Load the definition once — reused for form check + optional END check.
        Activity activityDef = activityRepository.findById(instance.getActividadId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity",
                        instance.getActividadId().toHexString()));

        // Form gate: required forms must be submitted before completion.
        if (Boolean.TRUE.equals(activityDef.getRequiereFormulario())
                && !formService.hasResponse(instance.getId())) {
            throw new BadRequestException(
                    "Cannot complete task: form must be submitted before completion");
        }

        LocalDateTime now = LocalDateTime.now();
        instance.setEstado(ACTIVITY_COMPLETED);
        instance.setFechaFin(now);
        activityInstanceRepository.save(instance);

        saveHistory(instance.getTramiteId(), instance.getActividadId(), userObjectId, ACTION_COMPLETED, now);

        // Follow outgoing flows and create the next instance(s).
        List<Flow> outgoingFlows = flowRepository.findByActividadOrigenId(instance.getActividadId());
        Procedure caseFile = procedureRepository.findById(instance.getTramiteId())
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile",
                        instance.getTramiteId().toHexString()));

        if (outgoingFlows.isEmpty()) {
            checkAndCompleteProcess(caseFile, now);
        } else {
            List<Flow> flowsToFollow = evaluateFlows(outgoingFlows);

            for (Flow flow : flowsToFollow) {
                Activity nextActivity = activityRepository.findById(flow.getActividadDestinoId())
                        .orElseThrow(() -> new ResourceNotFoundException("Activity",
                                flow.getActividadDestinoId().toHexString()));

                ActivityInstance nextInstance = buildInstance(caseFile.getId(), nextActivity, now);
                ActivityInstance savedNext = activityInstanceRepository.save(nextInstance);

                saveHistory(caseFile.getId(), nextActivity.getId(), userObjectId, ACTION_TRANSITION, now);

                log.info("Workflow transition: caseFile={}, from={}, to={}, flowType={}",
                        caseFile.getId(), instance.getActividadId(),
                        nextActivity.getId(), flow.getTipo());

                // Auto-complete END nodes so the process wraps up without
                // requiring an operator interaction.
                if ("END".equals(nextActivity.getTipo())) {
                    savedNext.setEstado(ACTIVITY_COMPLETED);
                    savedNext.setFechaInicio(now);
                    savedNext.setFechaFin(now);
                    activityInstanceRepository.save(savedNext);

                    saveHistory(caseFile.getId(), nextActivity.getId(), userObjectId, ACTION_COMPLETED, now);
                    checkAndCompleteProcess(caseFile, now);
                }
            }
        }

        caseFile = procedureRepository.findById(caseFile.getId()).orElse(caseFile);

        CaseFileResponseDTO response = caseFileMapper.toResponse(caseFile);
        List<ActivityInstance> activeInstances = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .filter(i -> !ACTIVITY_COMPLETED.equals(i.getEstado()))
                .toList();

        List<ObjectId> activityIds = activeInstances.stream()
                .map(ActivityInstance::getActividadId)
                .distinct()
                .toList();
        Map<ObjectId, Activity> activityMap = activityRepository.findAllById(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        response.setCurrentActivities(activeInstances.stream()
                .map(i -> activityInstanceMapper.toResponse(i, activityMap.get(i.getActividadId())))
                .toList());

        return response;
    }

    // ── Instance construction (shared by startProcess + completeActivity) ──

    /**
     * Builds a new WAITING instance for the given activity, copying its
     * eligible pool from the definition. Kept in one place so both the
     * initial instance and all transition-created ones stay consistent.
     */
    private ActivityInstance buildInstance(ObjectId caseFileId, Activity activity, LocalDateTime now) {
        List<ObjectId> pool = resolvePool(activity);
        return ActivityInstance.builder()
                .tramiteId(caseFileId)
                .actividadId(activity.getId())
                .estado(ACTIVITY_WAITING)
                .assignedUserIds(pool)
                .claimedBy(null)
                .createdAt(now)
                .build();
    }

    /**
     * Parses the definition's {@code assignedUserIds} (persisted as hex
     * strings for ease of editing in the designer) into a list of
     * {@link ObjectId}. Malformed ids are dropped silently — they would
     * simply be un-claimable and surface as "empty pool" in validation.
     */
    private List<ObjectId> resolvePool(Activity activity) {
        List<String> raw = activity.getAssignedUserIds();
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<ObjectId> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && ObjectId.isValid(s)) {
                out.add(new ObjectId(s));
            }
        }
        return out;
    }

    // ── Flow Evaluation ────────────────────────────────────────────────────

    private List<Flow> evaluateFlows(List<Flow> outgoingFlows) {
        if (outgoingFlows.size() == 1) {
            return outgoingFlows;
        }

        boolean hasParallel = outgoingFlows.stream()
                .anyMatch(f -> "PARALLEL".equals(f.getTipo()));
        if (hasParallel) {
            return outgoingFlows;
        }

        List<Flow> conditionalFlows = outgoingFlows.stream()
                .filter(f -> "CONDITIONAL".equals(f.getTipo()))
                .toList();

        if (!conditionalFlows.isEmpty()) {
            for (Flow flow : conditionalFlows) {
                if (flow.getCondicion() != null && evaluateCondition(flow.getCondicion())) {
                    return List.of(flow);
                }
            }
            Flow defaultFlow = conditionalFlows.stream()
                    .filter(f -> f.getCondicion() == null || f.getCondicion().isBlank())
                    .findFirst()
                    .orElse(conditionalFlows.get(0));
            return List.of(defaultFlow);
        }

        List<Flow> loopFlows = outgoingFlows.stream()
                .filter(f -> "LOOP".equals(f.getTipo()))
                .toList();
        if (!loopFlows.isEmpty()) {
            return loopFlows;
        }

        return outgoingFlows;
    }

    private boolean evaluateCondition(String condition) {
        log.debug("Evaluating condition (mocked): {}", condition);
        return true;
    }

    private void checkAndCompleteProcess(Procedure caseFile, LocalDateTime now) {
        List<ActivityInstance> pendingInstances = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .filter(i -> !ACTIVITY_COMPLETED.equals(i.getEstado()))
                .toList();

        if (pendingInstances.isEmpty()) {
            caseFile.setEstado(STATUS_COMPLETED);
            caseFile.setFechaFin(now);
            procedureRepository.save(caseFile);
            log.info("Process completed: caseFile={}", caseFile.getId());
        }
    }

    private void saveHistory(ObjectId caseFileId, ObjectId activityId,
                             ObjectId userId, String action, LocalDateTime timestamp) {
        ProcedureHistory history = ProcedureHistory.builder()
                .tramiteId(caseFileId)
                .actividadId(activityId)
                .usuarioId(userId)
                .accion(action)
                .fecha(timestamp)
                .build();
        procedureHistoryRepository.save(history);
    }

    private ActivityInstance findInstanceOrThrow(String activityInstanceId) {
        ObjectId objectId = parseObjectId(activityInstanceId, "activityInstanceId");
        return activityInstanceRepository.findById(objectId)
                .orElseThrow(() -> new ResourceNotFoundException("ActivityInstance", activityInstanceId));
    }

    private String generateCaseCode() {
        return "CASE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
