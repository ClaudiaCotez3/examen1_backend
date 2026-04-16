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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core workflow engine responsible for:
 * - Starting processes (creating case files from a policy version)
 * - Advancing activities through the workflow (start / complete)
 * - Evaluating outgoing flows and creating next activity instances
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

    private final CaseFileMapper caseFileMapper;
    private final ActivityInstanceMapper activityInstanceMapper;

    // ── Start Process ──────────────────────────────────────────────────────

    /**
     * Creates a new case file (process instance) from a policy version.
     * Finds the START activity, creates the first ActivityInstance in WAITING state,
     * and records the event in process history.
     */
    public CaseFileResponseDTO startProcess(String policyVersionId) {
        ObjectId versionObjectId = parseObjectId(policyVersionId, "policyVersionId");

        // Load and validate policy version
        PolicyVersion version = policyVersionRepository.findById(versionObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyVersion", policyVersionId));

        if (!"ACTIVE".equals(version.getEstado())) {
            throw new BadRequestException("Cannot start process: policy version is not ACTIVE");
        }

        // Find START activity for this policy
        List<Activity> startActivities = activityRepository
                .findByPoliticaIdAndTipo(version.getPoliticaId(), "START");

        if (startActivities.isEmpty()) {
            throw new BadRequestException("Policy has no START activity defined");
        }
        Activity startActivity = startActivities.get(0);

        // Create case file (Procedure / tramite)
        LocalDateTime now = LocalDateTime.now();
        Procedure caseFile = Procedure.builder()
                .codigo(generateCaseCode())
                .versionPoliticaId(versionObjectId)
                .estado(STATUS_ACTIVE)
                .fechaInicio(now)
                .build();
        Procedure savedCaseFile = procedureRepository.save(caseFile);

        // Create first activity instance in WAITING state
        ActivityInstance firstInstance = ActivityInstance.builder()
                .tramiteId(savedCaseFile.getId())
                .actividadId(startActivity.getId())
                .estado(ACTIVITY_WAITING)
                .build();
        ActivityInstance savedInstance = activityInstanceRepository.save(firstInstance);

        // Record history
        saveHistory(savedCaseFile.getId(), startActivity.getId(), null, ACTION_STARTED, now);

        log.info("Process started: caseFile={}, policy={}, startActivity={}",
                savedCaseFile.getId(), version.getPoliticaId(), startActivity.getId());

        // Build response
        CaseFileResponseDTO response = caseFileMapper.toResponse(savedCaseFile);
        response.setCurrentActivities(List.of(
                activityInstanceMapper.toResponse(savedInstance, startActivity)));
        return response;
    }

    // ── Start Activity ─────────────────────────────────────────────────────

    /**
     * Transitions an activity instance from WAITING to IN_PROGRESS atomically.
     *
     * Uses findAndModify with a conditional filter (estado = en_espera) so that only ONE
     * caller can win the transition — if two operators click "Start" simultaneously,
     * the second call receives null and we throw a clear 400 error.
     *
     * When a userId is provided, the user is assigned in the same atomic operation
     * (satisfies Phase 4 task: "Assign automatically when user clicks Start").
     */
    public ActivityInstanceResponseDTO startActivity(String activityInstanceId, String userId) {
        ObjectId instanceObjectId = parseObjectId(activityInstanceId, "activityInstanceId");
        ObjectId userObjectId = userId != null ? parseObjectId(userId, "userId") : null;
        LocalDateTime now = LocalDateTime.now();

        Query filter = new Query(Criteria.where("_id").is(instanceObjectId)
                .and("estado").is(ACTIVITY_WAITING));
        Update update = new Update()
                .set("estado", ACTIVITY_IN_PROGRESS)
                .set("fecha_inicio", now);
        if (userObjectId != null) {
            update.set("asignado_a", userObjectId);
        }

        ActivityInstance updated = mongoTemplate.findAndModify(
                filter,
                update,
                FindAndModifyOptions.options().returnNew(true),
                ActivityInstance.class
        );

        if (updated == null) {
            // Either the instance does not exist or it is no longer WAITING
            ActivityInstance existing = mongoTemplate.findById(instanceObjectId, ActivityInstance.class);
            if (existing == null) {
                throw new ResourceNotFoundException("ActivityInstance", activityInstanceId);
            }
            throw new BadRequestException(
                    "Cannot start activity: current status is '" + existing.getEstado()
                            + "'. Only WAITING activities can be started.");
        }

        saveHistory(updated.getTramiteId(), updated.getActividadId(), userObjectId, ACTION_STARTED, now);

        Activity activity = activityRepository.findById(updated.getActividadId()).orElse(null);
        return activityInstanceMapper.toResponse(updated, activity);
    }

    // ── Complete Activity (Core Workflow Logic) ────────────────────────────

    /**
     * Completes an activity instance and advances the workflow.
     * Evaluates outgoing flows and creates next activity instance(s) based on flow type:
     * - LINEAR: creates one next activity instance
     * - CONDITIONAL: evaluates condition (mocked for now), picks one path
     * - PARALLEL: creates multiple activity instances simultaneously
     * - LOOP: returns to a previous activity
     *
     * If the next activity is an END node, the case file is marked as COMPLETED.
     */
    public CaseFileResponseDTO completeActivity(String activityInstanceId, String userId) {
        ActivityInstance instance = findInstanceOrThrow(activityInstanceId);

        // State validation: only IN_PROGRESS activities can be completed
        if (!ACTIVITY_IN_PROGRESS.equals(instance.getEstado())) {
            throw new BadRequestException(
                    "Cannot complete activity: current status is '" + instance.getEstado()
                            + "'. Only IN_PROGRESS activities can be completed.");
        }

        LocalDateTime now = LocalDateTime.now();
        ObjectId userObjectId = userId != null ? parseObjectId(userId, "userId") : null;

        // Mark current activity as completed
        instance.setEstado(ACTIVITY_COMPLETED);
        instance.setFechaFin(now);
        activityInstanceRepository.save(instance);

        // Record completion in history
        saveHistory(instance.getTramiteId(), instance.getActividadId(), userObjectId, ACTION_COMPLETED, now);

        // Find outgoing flows from the completed activity
        List<Flow> outgoingFlows = flowRepository.findByActividadOrigenId(instance.getActividadId());

        // Create next activity instances based on flow evaluation
        List<ActivityInstance> nextInstances = new ArrayList<>();
        Procedure caseFile = procedureRepository.findById(instance.getTramiteId())
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile",
                        instance.getTramiteId().toHexString()));

        if (outgoingFlows.isEmpty()) {
            // No outgoing flows: check if process should complete
            checkAndCompleteProcess(caseFile, now);
        } else {
            List<Flow> flowsToFollow = evaluateFlows(outgoingFlows);

            for (Flow flow : flowsToFollow) {
                Activity nextActivity = activityRepository.findById(flow.getActividadDestinoId())
                        .orElseThrow(() -> new ResourceNotFoundException("Activity",
                                flow.getActividadDestinoId().toHexString()));

                // Create next activity instance
                ActivityInstance nextInstance = ActivityInstance.builder()
                        .tramiteId(caseFile.getId())
                        .actividadId(nextActivity.getId())
                        .estado(ACTIVITY_WAITING)
                        .build();
                ActivityInstance savedNext = activityInstanceRepository.save(nextInstance);
                nextInstances.add(savedNext);

                // Record transition in history
                saveHistory(caseFile.getId(), nextActivity.getId(), userObjectId, ACTION_TRANSITION, now);

                log.info("Workflow transition: caseFile={}, from={}, to={}, flowType={}",
                        caseFile.getId(), instance.getActividadId(),
                        nextActivity.getId(), flow.getTipo());

                // If the next activity is END, auto-complete it and finish the process
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

        // Reload case file (status may have changed)
        caseFile = procedureRepository.findById(caseFile.getId()).orElse(caseFile);

        // Build response with current active activities
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

    // ── Flow Evaluation ────────────────────────────────────────────────────

    /**
     * Evaluates outgoing flows to determine which paths to follow.
     * - LINEAR: follow the single path
     * - CONDITIONAL: evaluate condition (mocked — follows first flow with condition, or default)
     * - PARALLEL: follow ALL outgoing paths simultaneously
     * - LOOP: follow back to previous activity
     */
    private List<Flow> evaluateFlows(List<Flow> outgoingFlows) {
        if (outgoingFlows.size() == 1) {
            return outgoingFlows;
        }

        // Check if any flow is PARALLEL — if so, follow all paths
        boolean hasParallel = outgoingFlows.stream()
                .anyMatch(f -> "PARALLEL".equals(f.getTipo()));
        if (hasParallel) {
            return outgoingFlows;
        }

        // For CONDITIONAL flows: evaluate conditions
        // Mock implementation: pick the first flow with a non-null condition,
        // or fall back to the first flow without a condition (default path)
        List<Flow> conditionalFlows = outgoingFlows.stream()
                .filter(f -> "CONDITIONAL".equals(f.getTipo()))
                .toList();

        if (!conditionalFlows.isEmpty()) {
            // Try to find a flow whose condition evaluates to true (mocked)
            for (Flow flow : conditionalFlows) {
                if (flow.getCondicion() != null && evaluateCondition(flow.getCondicion())) {
                    return List.of(flow);
                }
            }
            // No condition matched: take the default path (flow without condition)
            Flow defaultFlow = conditionalFlows.stream()
                    .filter(f -> f.getCondicion() == null || f.getCondicion().isBlank())
                    .findFirst()
                    .orElse(conditionalFlows.get(0));
            return List.of(defaultFlow);
        }

        // For LOOP flows: follow back to previous activity
        List<Flow> loopFlows = outgoingFlows.stream()
                .filter(f -> "LOOP".equals(f.getTipo()))
                .toList();
        if (!loopFlows.isEmpty()) {
            return loopFlows;
        }

        // Default: follow all LINEAR flows
        return outgoingFlows;
    }

    /**
     * Mock condition evaluator.
     * In a real implementation, this would parse and evaluate expressions
     * against form data or case file variables.
     * For now, always returns true.
     */
    private boolean evaluateCondition(String condition) {
        // TODO: Implement real condition evaluation logic
        // Could parse expressions like "amount > 1000" against case file data
        log.debug("Evaluating condition (mocked): {}", condition);
        return true;
    }

    // ── Process Completion Check ───────────────────────────────────────────

    /**
     * Checks if all activity instances for a case file are completed.
     * If so, marks the case file as COMPLETED.
     */
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

    // ── History ────────────────────────────────────────────────────────────

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

    // ── Helpers ────────────────────────────────────────────────────────────

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
