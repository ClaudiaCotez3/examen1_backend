package com.example.backend.service;

import com.example.backend.dto.ConsultationCaseDTO;
import com.example.backend.dto.CurrentStageDTO;
import com.example.backend.dto.LaneProgressDTO;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.BusinessPolicyMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Lane;
import com.example.backend.model.PolicyVersion;
import com.example.backend.model.Procedure;
import com.example.backend.model.User;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.LaneRepository;
import com.example.backend.repository.PolicyVersionRepository;
import com.example.backend.repository.ProcedureRepository;
import com.example.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Search + summarise trámites for the customer-attention console
 * ("Consultas"). Looks up cases by the reserved {@code cliente_*} fields
 * captured by the start form (see {@link BusinessPolicyMapper}) and
 * returns a per-case snapshot the UI can render as a search-result list
 * and as a lane-by-lane progress timeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultationService {

    private static final String STATE_WAITING = "en_espera";
    private static final String STATE_IN_PROGRESS = "en_proceso";
    private static final String STATE_COMPLETED = "finalizado";
    private static final String STATE_BLOCKED = "bloqueada";
    private static final String STATE_DISCARDED = "descartada";

    private final MongoTemplate mongoTemplate;
    private final ProcedureRepository procedureRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final BusinessPolicyRepository policyRepository;
    private final LaneRepository laneRepository;
    private final ActivityRepository activityRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final UserRepository userRepository;

    /**
     * Returns trámites whose start-form data contains any of the supplied
     * search terms (substring, case-insensitive) on the reserved
     * {@code cliente_email}, {@code cliente_nombre} or {@code cliente_ci}
     * fields. At least one of the params must be present; otherwise the
     * search returns nothing.
     */
    public List<ConsultationCaseDTO> search(String email, String name, String ci) {
        List<Criteria> ors = new ArrayList<>();
        if (notBlank(email)) {
            ors.add(Criteria.where("start_form_data." + BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD)
                    .regex(Pattern.quote(email.trim()), "i"));
        }
        if (notBlank(name)) {
            ors.add(Criteria.where("start_form_data." + BusinessPolicyMapper.CUSTOMER_NAME_FIELD)
                    .regex(Pattern.quote(name.trim()), "i"));
        }
        if (notBlank(ci)) {
            ors.add(Criteria.where("start_form_data." + BusinessPolicyMapper.CUSTOMER_CI_FIELD)
                    .regex(Pattern.quote(ci.trim()), "i"));
        }
        if (ors.isEmpty()) {
            return List.of();
        }

        Query query = new Query(new Criteria().orOperator(ors.toArray(new Criteria[0])));
        query.with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "fecha_inicio"));
        List<Procedure> procedures = mongoTemplate.find(query, Procedure.class);

        return procedures.stream().map(this::toDto).toList();
    }

    /** Single-case detail used to refresh the timeline after a search. */
    public ConsultationCaseDTO getCase(String caseFileId) {
        ObjectId id;
        try {
            id = new ObjectId(caseFileId);
        } catch (IllegalArgumentException ex) {
            throw new ResourceNotFoundException("CaseFile", caseFileId);
        }
        Procedure procedure = procedureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CaseFile", caseFileId));
        return toDto(procedure);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private ConsultationCaseDTO toDto(Procedure procedure) {
        PolicyVersion version = procedure.getVersionPoliticaId() == null
                ? null
                : policyVersionRepository.findById(procedure.getVersionPoliticaId()).orElse(null);
        BusinessPolicy policy = version == null || version.getPoliticaId() == null
                ? null
                : policyRepository.findById(version.getPoliticaId()).orElse(null);

        ObjectId policyId = policy == null ? null : policy.getId();
        List<Lane> lanes = policyId == null
                ? List.of()
                : laneRepository.findByPoliticaIdOrderByPosicionAsc(policyId);
        List<Activity> activities = policyId == null
                ? List.of()
                : activityRepository.findByPoliticaId(policyId);
        Map<ObjectId, Activity> activityById = activities.stream()
                .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));

        List<ActivityInstance> instances = activityInstanceRepository
                .findByTramiteId(procedure.getId());

        Map<String, Object> data = procedure.getStartFormData() == null
                ? Map.of()
                : procedure.getStartFormData();

        return ConsultationCaseDTO.builder()
                .caseId(procedure.getId().toHexString())
                .code(procedure.getCodigo())
                .policyId(policyId == null ? null : policyId.toHexString())
                .policyName(policy == null ? null : policy.getNombre())
                .status(procedure.getEstado())
                .startedAt(procedure.getFechaInicio())
                .finishedAt(procedure.getFechaFin())
                .customerName(stringOrNull(data.get(BusinessPolicyMapper.CUSTOMER_NAME_FIELD)))
                .customerEmail(stringOrNull(data.get(BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD)))
                .customerCi(stringOrNull(data.get(BusinessPolicyMapper.CUSTOMER_CI_FIELD)))
                .lanesProgress(buildLanesProgress(lanes, activities, instances))
                .currentStages(buildCurrentStages(lanes, activityById, instances))
                .build();
    }

    /**
     * Folds activity instances per lane and assigns COMPLETED / CURRENT /
     * PENDING. The rules are intentionally permissive:
     *   - if any activity in the lane is in_proceso / en_espera, lane is CURRENT.
     *   - else if every activity in the lane has at least one instance and
     *     all of them are finalizado / descartada, lane is COMPLETED.
     *   - else PENDING (still bloqueada or no instance yet).
     */
    private List<LaneProgressDTO> buildLanesProgress(List<Lane> lanes,
                                                     List<Activity> activities,
                                                     List<ActivityInstance> instances) {
        // activityId → laneId
        Map<ObjectId, ObjectId> laneByActivity = activities.stream()
                .filter(a -> a.getCalleId() != null)
                .collect(Collectors.toMap(Activity::getId, Activity::getCalleId, (a, b) -> a));

        // laneId → list of states aggregated from its instances
        Map<ObjectId, List<String>> statesByLane = new HashMap<>();
        for (ActivityInstance inst : instances) {
            ObjectId laneId = laneByActivity.get(inst.getActividadId());
            if (laneId == null) continue;
            statesByLane.computeIfAbsent(laneId, l -> new ArrayList<>()).add(inst.getEstado());
        }

        List<LaneProgressDTO> out = new ArrayList<>();
        for (Lane lane : lanes) {
            List<String> states = statesByLane.getOrDefault(lane.getId(), List.of());
            String status;
            if (states.isEmpty()) {
                status = "PENDING";
            } else if (states.stream().anyMatch(s -> STATE_IN_PROGRESS.equals(s)
                    || STATE_WAITING.equals(s))) {
                status = "CURRENT";
            } else if (states.stream().anyMatch(STATE_BLOCKED::equals)
                    && states.stream().noneMatch(STATE_COMPLETED::equals)) {
                status = "PENDING";
            } else if (states.stream().allMatch(s -> STATE_COMPLETED.equals(s)
                    || STATE_DISCARDED.equals(s))) {
                status = "COMPLETED";
            } else {
                status = "CURRENT";
            }
            out.add(LaneProgressDTO.builder()
                    .laneId(lane.getId().toHexString())
                    .laneName(lane.getNombre())
                    .position(lane.getPosicion() == null ? 0 : lane.getPosicion())
                    .status(status)
                    .build());
        }
        out.sort(Comparator.comparingInt(LaneProgressDTO::getPosition));
        return out;
    }

    private List<CurrentStageDTO> buildCurrentStages(List<Lane> lanes,
                                                     Map<ObjectId, Activity> activityById,
                                                     List<ActivityInstance> instances) {
        Map<ObjectId, Lane> laneById = lanes.stream()
                .collect(Collectors.toMap(Lane::getId, l -> l, (a, b) -> a));
        Map<ObjectId, String> claimerNameById = new HashMap<>();
        for (ActivityInstance inst : instances) {
            if (inst.getClaimedBy() != null && !claimerNameById.containsKey(inst.getClaimedBy())) {
                User user = userRepository.findById(inst.getClaimedBy()).orElse(null);
                if (user != null) {
                    claimerNameById.put(user.getId(),
                            user.getNombre() == null ? user.getEmail() : user.getNombre());
                }
            }
        }

        List<CurrentStageDTO> out = new ArrayList<>();
        for (ActivityInstance inst : instances) {
            String estado = inst.getEstado();
            // Surface anything actionable or being worked on. BLOCKED is
            // included so the consultor can see what is "next" too.
            if (!STATE_WAITING.equals(estado)
                    && !STATE_IN_PROGRESS.equals(estado)
                    && !STATE_BLOCKED.equals(estado)) {
                continue;
            }
            Activity activity = activityById.get(inst.getActividadId());
            Lane lane = activity == null ? null : laneById.get(activity.getCalleId());
            out.add(CurrentStageDTO.builder()
                    .activityInstanceId(inst.getId() == null ? null : inst.getId().toHexString())
                    .laneName(lane == null ? null : lane.getNombre())
                    .activityName(activity == null ? null : activity.getNombre())
                    .state(normalizeState(estado))
                    .claimedByName(inst.getClaimedBy() == null
                            ? null
                            : claimerNameById.get(inst.getClaimedBy()))
                    .since(inst.getFechaInicio() != null ? inst.getFechaInicio() : inst.getCreatedAt())
                    .build());
        }
        return out;
    }

    private String normalizeState(String estado) {
        if (estado == null) return null;
        return switch (estado) {
            case STATE_WAITING -> "WAITING";
            case STATE_IN_PROGRESS -> "IN_PROGRESS";
            case STATE_BLOCKED -> "BLOCKED";
            default -> estado;
        };
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
