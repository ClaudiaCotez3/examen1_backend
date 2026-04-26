package com.example.backend.service;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.dto.CaseFileResponseDTO;
import com.example.backend.dto.StartCaseRequestDTO;
import com.example.backend.dto.StartCaseResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.ActivityInstanceMapper;
import com.example.backend.mapper.CaseFileMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Flow;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.PolicyVersion;
import com.example.backend.model.Procedure;
import com.example.backend.model.ProcedureHistory;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String VERSION_ACTIVE = "ACTIVE";
    private static final String VERSION_INACTIVE = "INACTIVE";

    private static final String POLICY_ARCHIVED = "ARCHIVED";

    private static final String ACTION_STARTED = "STARTED";
    private static final String ACTION_COMPLETED = "COMPLETED";
    private static final String ACTION_TRANSITION = "TRANSITION";

    private final BusinessPolicyRepository businessPolicyRepository;
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
     * Finds the START activity, creates the first ActivityInstance in WAITING state,
     * and records the event in process history.
     */
    public CaseFileResponseDTO startProcess(String policyVersionId) {
        ObjectId versionObjectId = parseObjectId(policyVersionId, "policyVersionId");

        // Load and validate policy version
        PolicyVersion version = policyVersionRepository.findById(versionObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyVersion", policyVersionId));

        if (!VERSION_ACTIVE.equals(version.getEstado())) {
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

        // START is a marker event in BPMN: record it and immediately advance
        // so the operator pool sees a real TASK instead of an idle START.
        ActivityInstance startInstance = activityInstanceRepository.save(ActivityInstance.builder()
                .tramiteId(savedCaseFile.getId())
                .actividadId(startActivity.getId())
                .estado(ACTIVITY_WAITING)
                .createdAt(now)
                .build());
        saveHistory(savedCaseFile.getId(), startActivity.getId(), null, ACTION_STARTED, now);

        completeStartAndAdvance(savedCaseFile, startInstance, startActivity, null, now);

        log.info("Process started: caseFile={}, policy={}, startActivity={}",
                savedCaseFile.getId(), version.getPoliticaId(), startActivity.getId());

        return buildCaseFileResponse(procedureRepository.findById(savedCaseFile.getId()).orElse(savedCaseFile));
    }

    // ── Start Case (consultor entry point) ─────────────────────────────────

    /**
     * Boots a new case from the consultor-facing payload: takes a policy id
     * plus the structured start-form data, validates that data against the
     * policy's start form schema, resolves (or auto-publishes) the ACTIVE
     * version, and then runs the same start-process bootstrap as
     * {@link #startProcess(String)}.
     *
     * The auto-publish behaviour exists so consultores can open a case
     * against a freshly authored policy without an admin having to remember
     * to "Activate" a version first — if no ACTIVE version is found we
     * snapshot the current draft and activate it on the spot.
     */
    public StartCaseResponseDTO startCase(StartCaseRequestDTO request) {
        if (request == null || request.getPolicyId() == null || request.getPolicyId().isBlank()) {
            throw new BadRequestException("policyId is required");
        }

        ObjectId policyObjectId = parseObjectId(request.getPolicyId(), "policyId");
        BusinessPolicy policy = businessPolicyRepository.findById(policyObjectId)
                .orElseThrow(() -> new ResourceNotFoundException("BusinessPolicy", request.getPolicyId()));

        if (POLICY_ARCHIVED.equals(policy.getEstado())) {
            throw new BadRequestException("Cannot start a case for an archived policy");
        }

        validateStartFormData(policy, request.getStartFormData());

        PolicyVersion activeVersion = resolveOrPublishActiveVersion(policy);

        Activity startActivity = activityRepository
                .findByPoliticaIdAndTipo(policyObjectId, "START")
                .stream()
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Policy has no START activity defined"));

        LocalDateTime now = LocalDateTime.now();
        Procedure caseFile = procedureRepository.save(Procedure.builder()
                .codigo(generateCaseCode())
                .versionPoliticaId(activeVersion.getId())
                .estado(STATUS_ACTIVE)
                .fechaInicio(now)
                .startFormData(request.getStartFormData())
                .build());

        ActivityInstance startInstance = activityInstanceRepository.save(ActivityInstance.builder()
                .tramiteId(caseFile.getId())
                .actividadId(startActivity.getId())
                .estado(ACTIVITY_WAITING)
                .createdAt(now)
                .build());
        saveHistory(caseFile.getId(), startActivity.getId(), null, ACTION_STARTED, now);

        // Auto-complete the START marker and create the next-activity
        // instances so the operator pool sees the first real TASK as soon
        // as the consultor finishes the start form.
        completeStartAndAdvance(caseFile, startInstance, startActivity, null, now);

        log.info("Case started: caseFile={}, policy={}, version={}",
                caseFile.getId(), policyObjectId, activeVersion.getId());

        return StartCaseResponseDTO.builder()
                .caseId(caseFile.getId().toHexString())
                .status("CREATED")
                .code(caseFile.getCodigo())
                .build();
    }

    private void validateStartFormData(BusinessPolicy policy, Map<String, Object> startFormData) {
        FormDefinition definition = policy.getStartFormDefinition();
        if (definition == null || definition.getFields() == null || definition.getFields().isEmpty()) {
            return; // policy has no start form — nothing to validate against
        }
        Map<String, Object> data = startFormData != null ? startFormData : Collections.emptyMap();
        formService.validateFormData(definition, data);
    }

    /**
     * Returns the ACTIVE version for this policy, auto-publishing one (and
     * activating it) when none exists yet. The autopublish path is only
     * reached on the very first case for a brand-new policy.
     */
    private PolicyVersion resolveOrPublishActiveVersion(BusinessPolicy policy) {
        return policyVersionRepository
                .findByPoliticaIdAndEstado(policy.getId(), VERSION_ACTIVE)
                .orElseGet(() -> autoPublishVersion(policy));
    }

    private PolicyVersion autoPublishVersion(BusinessPolicy policy) {
        List<PolicyVersion> existing = policyVersionRepository
                .findByPoliticaIdOrderByNumeroVersionDesc(policy.getId());
        int nextNumber = existing.isEmpty() ? 1 : existing.get(0).getNumeroVersion() + 1;

        // Demote any prior INACTIVE-but-stale rows just in case the data set
        // is in an inconsistent state — we want exactly one ACTIVE version.
        existing.stream()
                .filter(v -> VERSION_ACTIVE.equals(v.getEstado()))
                .forEach(v -> {
                    v.setEstado(VERSION_INACTIVE);
                    policyVersionRepository.save(v);
                });

        PolicyVersion published = PolicyVersion.builder()
                .politicaId(policy.getId())
                .numeroVersion(nextNumber)
                .estado(VERSION_ACTIVE)
                .bpmnXmlSnapshot(policy.getBpmnXml())
                .fechaPublicacion(LocalDateTime.now())
                .build();
        return policyVersionRepository.save(published);
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

        // Phase 5 — gate completion on the form when the activity declares one.
        // Loaded eagerly because the activity is also needed below; reusing the
        // same lookup avoids a second round-trip for the form check.
        Activity activityDef = activityRepository.findById(instance.getActividadId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity",
                        instance.getActividadId().toHexString()));
        if (Boolean.TRUE.equals(activityDef.getRequiereFormulario())
                && !formService.hasResponse(instance.getId())) {
            throw new BadRequestException(
                    "Cannot complete activity: form must be submitted before completion");
        }

        LocalDateTime now = LocalDateTime.now();
        ObjectId userObjectId = userId != null ? parseObjectId(userId, "userId") : null;

        // Mark current activity as completed
        instance.setEstado(ACTIVITY_COMPLETED);
        instance.setFechaFin(now);
        activityInstanceRepository.save(instance);

        // Record completion in history
        saveHistory(instance.getTramiteId(), instance.getActividadId(), userObjectId, ACTION_COMPLETED, now);

        Procedure caseFile = procedureRepository.findById(instance.getTramiteId())
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile",
                        instance.getTramiteId().toHexString()));

        advanceFromCompleted(caseFile, instance.getActividadId(), userObjectId, now);

        // Reload case file (status may have flipped to COMPLETED inside advance).
        caseFile = procedureRepository.findById(caseFile.getId()).orElse(caseFile);
        return buildCaseFileResponse(caseFile);
    }

    // ── Workflow advancement helpers ───────────────────────────────────────

    /**
     * Auto-completes a freshly created START instance and immediately creates
     * the next-activity instances. Keeps the case from sitting on a no-op
     * START marker — the operator pool needs to see real TASKs the moment a
     * consultor opens the case.
     *
     * Resolves the policy id from the START activity first and falls back to
     * looking up the procedure's PolicyVersion → politicaId, so the orphan
     * sweep still runs even if a particular Activity ended up persisted
     * without its politicaId for some legacy reason.
     */
    private void completeStartAndAdvance(Procedure caseFile, ActivityInstance startInstance,
                                         Activity startActivity, ObjectId userId, LocalDateTime now) {
        startInstance.setEstado(ACTIVITY_COMPLETED);
        startInstance.setFechaInicio(now);
        startInstance.setFechaFin(now);
        activityInstanceRepository.save(startInstance);
        saveHistory(caseFile.getId(), startActivity.getId(), userId, ACTION_COMPLETED, now);

        advanceFromCompleted(caseFile, startActivity.getId(), userId, now);

        // The cascade only walks reachable nodes from START. The product
        // requires the operator Kanban to surface every TASK of the policy
        // — even tasks the admin left disconnected on the canvas — so we
        // run a second pass that materialises any orphan TASK that did
        // not get an instance through the graph walk.
        ObjectId policyId = startActivity.getPoliticaId();
        if (policyId == null && caseFile.getVersionPoliticaId() != null) {
            policyId = policyVersionRepository.findById(caseFile.getVersionPoliticaId())
                    .map(PolicyVersion::getPoliticaId)
                    .orElse(null);
        }
        materialiseOrphanTasks(caseFile, policyId, userId, now);
    }

    /**
     * Creates a WAITING instance for every TASK of the policy that does NOT
     * already have an instance attached to the given case. Used as a safety
     * net for diagrams where the admin left tasks unconnected: the operator
     * still needs to see them on the Kanban so they can claim and complete.
     */
    private void materialiseOrphanTasks(Procedure caseFile, ObjectId policyId,
                                        ObjectId userId, LocalDateTime now) {
        if (policyId == null) return;
        List<Activity> tasks = activityRepository.findByPoliticaIdAndTipo(policyId, "TASK");
        if (tasks.isEmpty()) return;

        Set<ObjectId> alreadyMaterialised = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .map(ActivityInstance::getActividadId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        for (Activity task : tasks) {
            if (alreadyMaterialised.contains(task.getId())) {
                continue;
            }
            activityInstanceRepository.save(ActivityInstance.builder()
                    .tramiteId(caseFile.getId())
                    .actividadId(task.getId())
                    .estado(ACTIVITY_WAITING)
                    .assignedUserIds(resolveAssignees(task))
                    .createdAt(now)
                    .build());
            saveHistory(caseFile.getId(), task.getId(), userId, ACTION_TRANSITION, now);
            log.info("Materialised orphan TASK {} ('{}') for caseFile {}",
                    task.getId(), task.getNombre(), caseFile.getId());
        }
    }

    /**
     * Creates ActivityInstances for the activities downstream of {@code fromActivityId}.
     *
     * Behaviour:
     *   - END        → auto-completed in place, then checks if the case is finished.
     *   - TASK       → instance is created in WAITING and the cascade continues
     *                  through *its* outgoing flows. This pre-materialises every
     *                  reachable TASK on a linear chain so the operator Kanban
     *                  shows the full pipeline up front instead of revealing
     *                  the next task only after the previous one completes.
     *   - DECISION   → instance is created but the cascade stops, because the
     *                  branch can only be picked at runtime.
     *
     * Idempotent: if an instance for {@code (caseFile, activity)} already
     * exists, it is reused instead of duplicated. This is what lets
     * {@link #completeActivity(String, String)} call back into this method
     * after a TASK is finished without re-creating already-cascaded successors.
     */
    private void advanceFromCompleted(Procedure caseFile, ObjectId fromActivityId,
                                      ObjectId userId, LocalDateTime now) {
        cascadeAdvance(caseFile, fromActivityId, userId, now, new HashSet<>());
    }

    private void cascadeAdvance(Procedure caseFile, ObjectId fromActivityId,
                                ObjectId userId, LocalDateTime now,
                                Set<ObjectId> visited) {
        if (!visited.add(fromActivityId)) {
            return; // cycle / already-walked node
        }

        List<Flow> outgoing = flowRepository.findByActividadOrigenId(fromActivityId);
        if (outgoing.isEmpty()) {
            checkAndCompleteProcess(caseFile, now);
            return;
        }

        // Iterate EVERY outgoing flow regardless of type (LINEAR / CONDITIONAL
        // / PARALLEL / LOOP). The Kanban needs to surface the full operator
        // pipeline at start time, even when flows are CONDITIONAL — those
        // edges still terminate on a real TASK that the operator owns. The
        // only branches we don't follow are gateways (DECISION) and END,
        // which are handled below.
        for (Flow flow : outgoing) {
            Activity next = activityRepository.findById(flow.getActividadDestinoId())
                    .orElseThrow(() -> new ResourceNotFoundException("Activity",
                            flow.getActividadDestinoId().toHexString()));

            ActivityInstance nextInstance = findOrCreateInstance(caseFile, next, now, userId, flow);

            if ("END".equals(next.getTipo())) {
                if (!ACTIVITY_COMPLETED.equals(nextInstance.getEstado())) {
                    nextInstance.setEstado(ACTIVITY_COMPLETED);
                    nextInstance.setFechaInicio(now);
                    nextInstance.setFechaFin(now);
                    activityInstanceRepository.save(nextInstance);
                    saveHistory(caseFile.getId(), next.getId(), userId, ACTION_COMPLETED, now);
                }
                checkAndCompleteProcess(caseFile, now);
            } else if ("TASK".equals(next.getTipo())) {
                // Pre-materialise every reachable TASK so they all show up
                // on the operator Kanban at once.
                cascadeAdvance(caseFile, next.getId(), userId, now, visited);
            } else if ("DECISION".equals(next.getTipo())) {
                // Gateways are logical nodes that no operator works on. Mark
                // the gateway instance completed in place and continue the
                // cascade through ALL of its outgoing branches so the TASKs
                // sitting behind the decision still surface to their
                // assignees up front. This prioritises "operator sees every
                // task they own" over strict BPMN branch evaluation, which
                // is what the product requires.
                if (!ACTIVITY_COMPLETED.equals(nextInstance.getEstado())) {
                    nextInstance.setEstado(ACTIVITY_COMPLETED);
                    nextInstance.setFechaInicio(now);
                    nextInstance.setFechaFin(now);
                    activityInstanceRepository.save(nextInstance);
                    saveHistory(caseFile.getId(), next.getId(), userId, ACTION_COMPLETED, now);
                }
                cascadeAdvance(caseFile, next.getId(), userId, now, visited);
            }
            // Other types: fail safe by not over-creating downstream nodes.
        }
    }

    /**
     * Returns the existing WAITING/IN_PROGRESS/COMPLETED instance for the
     * given (case, activity) pair, or creates one if none exists. Keeps the
     * cascade and the post-completion advance from racing each other and
     * producing duplicates.
     */
    private ActivityInstance findOrCreateInstance(Procedure caseFile, Activity activity,
                                                  LocalDateTime now, ObjectId userId, Flow flow) {
        List<ActivityInstance> existing = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .filter(i -> activity.getId().equals(i.getActividadId()))
                .toList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        ActivityInstance created = activityInstanceRepository.save(ActivityInstance.builder()
                .tramiteId(caseFile.getId())
                .actividadId(activity.getId())
                .estado(ACTIVITY_WAITING)
                .assignedUserIds(resolveAssignees(activity))
                .createdAt(now)
                .build());

        saveHistory(caseFile.getId(), activity.getId(), userId, ACTION_TRANSITION, now);
        log.info("Workflow transition: caseFile={}, to={}, flowType={}",
                caseFile.getId(), activity.getId(), flow == null ? "—" : flow.getTipo());
        return created;
    }

    /**
     * Converts the activity's hex-string assignee list (kept as String on
     * the definition entity for historical reasons) into the ObjectId list
     * the runtime ActivityInstance stores. Invalid ids are dropped silently
     * — the policy designer is the source of truth, and a malformed id here
     * means the saved policy itself is broken; logging once at WARN level
     * is enough.
     */
    private List<ObjectId> resolveAssignees(Activity activity) {
        if (activity.getAssignedUserIds() == null || activity.getAssignedUserIds().isEmpty()) {
            return Collections.emptyList();
        }
        List<ObjectId> resolved = new ArrayList<>(activity.getAssignedUserIds().size());
        for (String raw : activity.getAssignedUserIds()) {
            if (raw != null && ObjectId.isValid(raw)) {
                resolved.add(new ObjectId(raw));
            } else if (raw != null && !raw.isBlank()) {
                log.warn("Skipping malformed assignee id '{}' on activity {}", raw, activity.getId());
            }
        }
        return resolved;
    }

    /** Builds the case-file response with currently active activity instances populated. */
    private CaseFileResponseDTO buildCaseFileResponse(Procedure caseFile) {
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
