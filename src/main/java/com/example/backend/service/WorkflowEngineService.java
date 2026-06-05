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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
    /**
     * Pre-materialised but not yet startable: a TASK whose upstream
     * predecessors haven't all completed. The operator Kanban renders
     * these with a lock icon and no "Tomar" button.
     */
    private static final String ACTIVITY_BLOCKED = "bloqueada";
    /**
     * The branch this instance lived on was not chosen at the gateway
     * decision point, so the task will never run. Hidden from the operator.
     */
    private static final String ACTIVITY_DISCARDED = "descartada";

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
    private final CaseDocumentService caseDocumentService;
    private final CustomerResolutionService customerResolutionService;

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

        // Opción B: customer identity is resolved ONCE, here at write time
        // (find-or-create by the reserved cliente_email / cliente_ci fields).
        // Downstream consumers (Repositorio, reportes, analítica) join by
        // cliente_id instead of re-running heuristics per read.
        ObjectId clienteId = customerResolutionService
                .resolveOrCreate(request.getStartFormData());

        LocalDateTime now = LocalDateTime.now();
        Procedure caseFile = procedureRepository.save(Procedure.builder()
                .codigo(generateCaseCode())
                .versionPoliticaId(activeVersion.getId())
                .estado(STATUS_ACTIVE)
                .fechaInicio(now)
                .startFormData(request.getStartFormData())
                .clienteId(clienteId)
                .build());

        // Gestión Documental (TAREA 4): the case IS a digital expediente from
        // birth — every attachment declared on the start form is registered
        // as a document of the trámite (metadata now, binary attachable later).
        try {
            caseDocumentService.registerStartFormDocuments(caseFile, policy.getStartFormDefinition());
        } catch (Exception e) {
            // Never break case creation because of the documental side-channel.
            log.warn("Could not register start-form documents for case {}", caseFile.getId(), e);
        }

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
        return completeActivity(activityInstanceId, userId, null);
    }

    /**
     * Completes a TASK and propagates the resulting state changes.
     *
     * @param decision optional APPROVED / REJECTED. When the just-completed
     *                 TASK feeds a DECISION gateway, the decision picks
     *                 which branch to keep (the others get discarded).
     */
    public CaseFileResponseDTO completeActivity(String activityInstanceId, String userId, String decision) {
        log.info("[Workflow] completeActivity instance={} userId={} decision={}",
                activityInstanceId, userId, decision);
        ActivityInstance instance = findInstanceOrThrow(activityInstanceId);

        if (!ACTIVITY_IN_PROGRESS.equals(instance.getEstado())) {
            throw new BadRequestException(
                    "Cannot complete activity: current status is '" + instance.getEstado()
                            + "'. Only IN_PROGRESS activities can be completed.");
        }

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

        instance.setEstado(ACTIVITY_COMPLETED);
        instance.setFechaFin(now);
        activityInstanceRepository.save(instance);
        saveHistory(instance.getTramiteId(), instance.getActividadId(), userObjectId, ACTION_COMPLETED, now);

        Procedure caseFile = procedureRepository.findById(instance.getTramiteId())
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile",
                        instance.getTramiteId().toHexString()));

        // Build a per-policy activity index once; reused by both the
        // decision resolver and the unblock pass.
        ObjectId policyId = activityDef.getPoliticaId();
        Map<ObjectId, Activity> activitiesById = policyId == null
                ? Collections.emptyMap()
                : activityRepository.findByPoliticaId(policyId).stream()
                        .collect(Collectors.toMap(Activity::getId, Function.identity()));

        // 1. If the completed task fed a DECISION gateway, prune the rejected
        //    branch first so its tasks don't get unblocked in the next step.
        resolveDecisionBranches(caseFile, instance.getActividadId(), decision, activitiesById, now);

        // 2. Promote freshly unblocked TASKs from BLOCKED → WAITING.
        refreshDownstreamStates(caseFile, instance.getActividadId(), activitiesById, now);

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

        // Materialise every TASK of the policy with its initial state
        // (WAITING for tasks with no upstream task, BLOCKED otherwise).
        // The dependency-aware engine takes over from completeActivity()
        // onward, unblocking tasks one branch at a time as predecessors
        // finish.
        ObjectId policyId = startActivity.getPoliticaId();
        if (policyId == null && caseFile.getVersionPoliticaId() != null) {
            policyId = policyVersionRepository.findById(caseFile.getVersionPoliticaId())
                    .map(PolicyVersion::getPoliticaId)
                    .orElse(null);
        }
        materialiseOrphanTasks(caseFile, policyId, userId, now);
    }

    /**
     * Creates an instance for every TASK of the policy that doesn't have one
     * yet. The initial estado depends on the graph of flows:
     *
     *   - WAITING   if the task has no upstream TASK predecessors (i.e. it
     *               sits right after START, or is the only task in its
     *               branch from START's point of view).
     *   - BLOCKED   if at least one upstream TASK exists; the operator
     *               sees it with a lock icon and can't claim it yet. As
     *               those upstream tasks complete, the engine flips the
     *               state to WAITING via {@link #refreshDownstreamStates}.
     *
     * DECISIONs are transparent for predecessor calculation: a TASK behind
     * a DECISION inherits the predecessors of the gateway (i.e. whichever
     * TASK precedes the DECISION) so it stays BLOCKED until the decision
     * is taken upstream.
     */
    private void materialiseOrphanTasks(Procedure caseFile, ObjectId policyId,
                                        ObjectId userId, LocalDateTime now) {
        if (policyId == null) return;
        List<Activity> tasks = activityRepository.findByPoliticaIdAndTipo(policyId, "TASK");
        if (tasks.isEmpty()) return;

        Map<ObjectId, Activity> activitiesById = activityRepository.findByPoliticaId(policyId).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));

        Set<ObjectId> alreadyMaterialised = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .map(ActivityInstance::getActividadId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        for (Activity task : tasks) {
            if (alreadyMaterialised.contains(task.getId())) {
                continue;
            }
            String initialState = hasUpstreamTaskPredecessor(task.getId(), activitiesById)
                    ? ACTIVITY_BLOCKED
                    : ACTIVITY_WAITING;
            activityInstanceRepository.save(ActivityInstance.builder()
                    .tramiteId(caseFile.getId())
                    .actividadId(task.getId())
                    .estado(initialState)
                    .assignedUserIds(resolveAssignees(task))
                    .createdAt(now)
                    .build());
            saveHistory(caseFile.getId(), task.getId(), userId, ACTION_TRANSITION, now);
            log.info("Materialised TASK {} ('{}') as {} for caseFile {}",
                    task.getId(), task.getNombre(), initialState, caseFile.getId());
        }
    }

    /**
     * True when there's at least one TASK upstream of {@code activityId}
     * along the flow graph. DECISIONs are walked through transparently —
     * we want the predecessor TASK that decides the gateway, not the
     * gateway itself.
     */
    private boolean hasUpstreamTaskPredecessor(ObjectId activityId,
                                               Map<ObjectId, Activity> activitiesById) {
        Set<ObjectId> visited = new HashSet<>();
        Deque<ObjectId> queue = new ArrayDeque<>();
        queue.add(activityId);
        while (!queue.isEmpty()) {
            ObjectId current = queue.poll();
            if (!visited.add(current)) continue;
            List<Flow> incoming = flowRepository.findByActividadDestinoId(current);
            for (Flow flow : incoming) {
                ObjectId src = flow.getActividadOrigenId();
                Activity srcAct = activitiesById.get(src);
                if (srcAct == null) continue;
                if ("TASK".equals(srcAct.getTipo())) return true;
                if (isGatewayType(srcAct.getTipo())) {
                    queue.add(src); // walk through the gateway (DECISION or PARALLEL)
                }
                // START / END predecessors don't count as blockers.
            }
        }
        return false;
    }

    /**
     * After a TASK completes, looks at every TASK reachable downstream
     * (walking through DECISIONs). For each one currently BLOCKED, checks
     * if all of its TASK-level predecessors are now COMPLETED or
     * DISCARDED — if so, flips it to WAITING so the operator can claim it.
     */
    private void refreshDownstreamStates(Procedure caseFile,
                                         ObjectId completedActivityId,
                                         Map<ObjectId, Activity> activitiesById,
                                         LocalDateTime now) {
        Map<ObjectId, ActivityInstance> instancesByActivity = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .filter(i -> i.getActividadId() != null)
                .collect(Collectors.toMap(ActivityInstance::getActividadId, Function.identity(),
                        (a, b) -> a));

        Set<ObjectId> visited = new HashSet<>();
        Deque<ObjectId> queue = new ArrayDeque<>();
        queue.add(completedActivityId);
        while (!queue.isEmpty()) {
            ObjectId current = queue.poll();
            if (!visited.add(current)) continue;
            for (Flow flow : flowRepository.findByActividadOrigenId(current)) {
                ObjectId targetId = flow.getActividadDestinoId();
                Activity target = activitiesById.get(targetId);
                if (target == null) continue;
                if ("TASK".equals(target.getTipo())) {
                    ActivityInstance inst = instancesByActivity.get(targetId);
                    if (inst != null
                            && ACTIVITY_BLOCKED.equals(inst.getEstado())
                            && allPredecessorsResolved(targetId, activitiesById, instancesByActivity)) {
                        inst.setEstado(ACTIVITY_WAITING);
                        activityInstanceRepository.save(inst);
                        log.info("Unblocked TASK {} ('{}') on caseFile {}",
                                targetId, target.getNombre(), caseFile.getId());
                    }
                } else if (isGatewayType(target.getTipo())) {
                    queue.add(targetId); // walk through gateway (DECISION or PARALLEL fork)
                }
            }
        }

        // If every non-discarded instance of the case is COMPLETED, close it.
        checkAndCompleteProcess(caseFile, now);
    }

    private boolean allPredecessorsResolved(ObjectId activityId,
                                            Map<ObjectId, Activity> activitiesById,
                                            Map<ObjectId, ActivityInstance> instancesByActivity) {
        Set<ObjectId> visited = new HashSet<>();
        Deque<ObjectId> queue = new ArrayDeque<>();
        queue.add(activityId);
        while (!queue.isEmpty()) {
            ObjectId current = queue.poll();
            if (!visited.add(current)) continue;
            for (Flow flow : flowRepository.findByActividadDestinoId(current)) {
                ObjectId src = flow.getActividadOrigenId();
                Activity srcAct = activitiesById.get(src);
                if (srcAct == null) continue;
                if ("TASK".equals(srcAct.getTipo())) {
                    ActivityInstance inst = instancesByActivity.get(src);
                    if (inst == null) continue;
                    String estado = inst.getEstado();
                    if (!ACTIVITY_COMPLETED.equals(estado) && !ACTIVITY_DISCARDED.equals(estado)) {
                        return false;
                    }
                } else if (isGatewayType(srcAct.getTipo())) {
                    // Walk through gateways. For a PARALLEL join this naturally
                    // requires EVERY upstream branch task to be resolved before
                    // the downstream task unblocks (AND-join semantics).
                    queue.add(src);
                }
            }
        }
        return true;
    }

    /**
     * Resolves a DECISION downstream of a just-completed TASK against the
     * decision label the operator picked (APPROVED / REJECTED). Tasks on
     * the chosen branch get a chance to unblock; tasks on the rejected
     * branch (and everything reachable from them) are recursively marked
     * DISCARDED so the operator never sees them.
     *
     * Fallback when the admin didn't label the gateway's branches: the
     * first outgoing flow is treated as the APPROVED path and the rest as
     * the REJECTED path. Order in {@code findByActividadOrigenId} is the
     * insertion order of the flow rows, which matches how the designer
     * wrote them out. The {@code workflow:branchLabel} extension is still
     * the recommended way to disambiguate — this only kicks in if the
     * admin didn't bother.
     */
    private void resolveDecisionBranches(Procedure caseFile,
                                         ObjectId taskJustCompletedId,
                                         String decision,
                                         Map<ObjectId, Activity> activitiesById,
                                         LocalDateTime now) {
        if (decision == null || decision.isBlank()) {
            log.info("[Workflow] decision empty — gateway resolution skipped");
            return;
        }

        Map<ObjectId, ActivityInstance> instancesByActivity = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .filter(i -> i.getActividadId() != null)
                .collect(Collectors.toMap(ActivityInstance::getActividadId, Function.identity(),
                        (a, b) -> a));

        for (Flow toGateway : flowRepository.findByActividadOrigenId(taskJustCompletedId)) {
            Activity gateway = activitiesById.get(toGateway.getActividadDestinoId());
            if (gateway == null || !"DECISION".equals(gateway.getTipo())) continue;

            List<Flow> branches = flowRepository.findByActividadOrigenId(gateway.getId());
            boolean anyLabelled = branches.stream()
                    .anyMatch(f -> f.getBranchLabel() != null && !f.getBranchLabel().isBlank());

            if (anyLabelled) {
                for (Flow branchFlow : branches) {
                    String label = branchFlow.getBranchLabel();
                    // Branches without a label are kept (couldn't tell what
                    // they are without admin input).
                    if (label == null || label.isBlank()) continue;
                    if (!matchesDecision(label, decision)) {
                        log.info("[Workflow] discarding branch '{}' on gateway {} (decision={})",
                                label, gateway.getId(), decision);
                        discardChain(branchFlow.getActividadDestinoId(), activitiesById,
                                instancesByActivity, now, caseFile);
                    }
                }
            } else if (branches.size() > 1) {
                // Fallback: first flow = APPROVED branch, rest = REJECTED.
                log.warn("[Workflow] gateway {} has no labelled branches — falling back to "
                        + "'first flow = APPROVED'", gateway.getId());
                boolean approved = "APPROVED".equalsIgnoreCase(decision)
                        || "APROBADO".equalsIgnoreCase(decision)
                        || "SI".equalsIgnoreCase(decision);
                for (int i = 0; i < branches.size(); i++) {
                    boolean keep = (approved && i == 0) || (!approved && i != 0);
                    if (!keep) {
                        log.info("[Workflow] discarding fallback branch index {} on gateway {} (decision={})",
                                i, gateway.getId(), decision);
                        discardChain(branches.get(i).getActividadDestinoId(), activitiesById,
                                instancesByActivity, now, caseFile);
                    }
                }
            }
        }
    }

    private boolean matchesDecision(String branchLabel, String decision) {
        if (branchLabel == null || decision == null) return false;
        // Equate APPROVED ↔ APROBADO / SI / YES; REJECTED ↔ RECHAZADO / NO.
        String norm = branchLabel.trim().toUpperCase();
        String dec = decision.trim().toUpperCase();
        if (norm.equals(dec)) return true;
        if (dec.equals("APPROVED") &&
                (norm.equals("APROBADO") || norm.equals("SI") || norm.equals("SÍ") || norm.equals("YES"))) {
            return true;
        }
        if (dec.equals("REJECTED") &&
                (norm.equals("RECHAZADO") || norm.equals("NO"))) {
            return true;
        }
        return false;
    }

    /**
     * Walks downstream from {@code startId} marking every BLOCKED/WAITING
     * task as DISCARDED. Stops at END nodes; transparent on DECISIONs.
     */
    private void discardChain(ObjectId startId,
                              Map<ObjectId, Activity> activitiesById,
                              Map<ObjectId, ActivityInstance> instancesByActivity,
                              LocalDateTime now,
                              Procedure caseFile) {
        Set<ObjectId> visited = new HashSet<>();
        Deque<ObjectId> queue = new ArrayDeque<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            ObjectId current = queue.poll();
            if (!visited.add(current)) continue;
            Activity act = activitiesById.get(current);
            if (act == null) continue;
            if ("END".equals(act.getTipo())) continue;
            if ("TASK".equals(act.getTipo())) {
                ActivityInstance inst = instancesByActivity.get(current);
                if (inst != null
                        && (ACTIVITY_BLOCKED.equals(inst.getEstado())
                            || ACTIVITY_WAITING.equals(inst.getEstado()))) {
                    inst.setEstado(ACTIVITY_DISCARDED);
                    inst.setFechaFin(now);
                    activityInstanceRepository.save(inst);
                    log.info("Discarded TASK {} ('{}') on caseFile {}",
                            current, act.getNombre(), caseFile.getId());
                }
            }
            for (Flow flow : flowRepository.findByActividadOrigenId(current)) {
                queue.add(flow.getActividadDestinoId());
            }
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
            } else if (isGatewayType(next.getTipo())) {
                // Gateways (DECISION or PARALLEL) are logical nodes that no
                // operator works on. Mark the gateway instance completed in
                // place and continue the cascade through ALL of its outgoing
                // branches so the TASKs sitting behind the gateway still
                // surface to their assignees up front. This prioritises
                // "operator sees every task they own" over strict BPMN branch
                // evaluation, which is what the product requires.
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
        // The case is done when there's nothing left to act on. Discarded
        // instances (rejected gateway branches) are considered terminal
        // for completion purposes — the operator never has to touch them.
        List<ActivityInstance> pendingInstances = activityInstanceRepository
                .findByTramiteId(caseFile.getId()).stream()
                .filter(i -> !ACTIVITY_COMPLETED.equals(i.getEstado())
                        && !ACTIVITY_DISCARDED.equals(i.getEstado()))
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

    /**
     * Gateways are logical, instance-less nodes the engine walks THROUGH when
     * computing predecessors / successors. Both the exclusive (DECISION) and
     * the parallel (PARALLEL) gateway are transparent for graph traversal.
     *
     * The difference between them lives elsewhere:
     *   - DECISION branches get pruned by {@link #resolveDecisionBranches}
     *     (one branch kept, the rest DISCARDED) and force the operator to pick
     *     APPROVED/REJECTED ({@code requiresDecision} in OperatorService).
     *   - PARALLEL is never pruned and never asks for a decision, so a fork
     *     unblocks ALL branches and a join waits for EVERY branch.
     */
    private boolean isGatewayType(String tipo) {
        return "DECISION".equals(tipo) || "PARALLEL".equals(tipo);
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
