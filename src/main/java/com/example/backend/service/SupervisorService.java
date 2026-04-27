package com.example.backend.service;

import com.example.backend.dto.BottleneckActivityDTO;
import com.example.backend.dto.OperatorPerformanceDTO;
import com.example.backend.dto.SupervisorOverviewDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Lane;
import com.example.backend.model.Procedure;
import com.example.backend.model.User;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.LaneRepository;
import com.example.backend.repository.ProcedureRepository;
import com.example.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic KPI computations for the Supervisor dashboard.
 *
 * Numbers come from a single pass over `instancias_actividad` joined
 * with the relevant `actividades`, `politicas_negocio`, `calles` and
 * `usuarios` documents. This service stays out of any predictive /
 * analytical logic — that lives in the FastAPI sidecar; we just hand
 * over the agreed-upon KPI shapes.
 */
@Service
@RequiredArgsConstructor
public class SupervisorService {

    private static final String STATE_WAITING = "en_espera";
    private static final String STATE_IN_PROGRESS = "en_proceso";
    private static final String STATE_COMPLETED = "finalizado";
    private static final String STATE_BLOCKED = "bloqueada";

    private static final int STALLED_THRESHOLD_DAYS = 7;

    private final ActivityInstanceRepository activityInstanceRepository;
    private final ActivityRepository activityRepository;
    private final ProcedureRepository procedureRepository;
    private final LaneRepository laneRepository;
    private final BusinessPolicyRepository businessPolicyRepository;
    private final UserRepository userRepository;

    // ── Overview (top-of-page KPIs) ───────────────────────────────────────

    public SupervisorOverviewDTO getOverview() {
        List<Procedure> procedures = procedureRepository.findAll();
        long activeCases = procedures.stream().filter(p -> "activo".equals(p.getEstado())).count();
        long completedCases = procedures.stream().filter(p -> "finalizado".equals(p.getEstado())).count();

        List<ActivityInstance> instances = activityInstanceRepository.findAll();
        long pending = instances.stream().filter(i -> STATE_WAITING.equals(i.getEstado())).count();
        long inProgress = instances.stream().filter(i -> STATE_IN_PROGRESS.equals(i.getEstado())).count();

        double p95Lead = percentileLeadMinutes(instances, 95);

        LocalDateTime stalledCutoff = LocalDateTime.now().minusDays(STALLED_THRESHOLD_DAYS);
        long stalled = procedures.stream()
                .filter(p -> "activo".equals(p.getEstado()))
                .filter(p -> p.getFechaInicio() != null && p.getFechaInicio().isBefore(stalledCutoff))
                .count();

        return SupervisorOverviewDTO.builder()
                .activeCases(activeCases)
                .completedCases(completedCases)
                .pendingTasks(pending)
                .inProgressTasks(inProgress)
                .p95LeadMinutes(p95Lead)
                .stalledCases(stalled)
                .stalledDaysThreshold(STALLED_THRESHOLD_DAYS)
                .build();
    }

    // ── Bottlenecks by activity ───────────────────────────────────────────

    public List<BottleneckActivityDTO> getBottlenecksByActivity() {
        List<Activity> activities = activityRepository.findAll().stream()
                .filter(a -> "TASK".equals(a.getTipo()))
                .toList();
        if (activities.isEmpty()) return List.of();

        Map<ObjectId, Activity> activityById = activities.stream()
                .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));
        Map<ObjectId, BusinessPolicy> policyById = businessPolicyRepository.findAll().stream()
                .collect(Collectors.toMap(BusinessPolicy::getId, p -> p, (a, b) -> a));
        Map<ObjectId, Lane> laneById = laneRepository.findAll().stream()
                .collect(Collectors.toMap(Lane::getId, l -> l, (a, b) -> a));

        List<ActivityInstance> all = activityInstanceRepository.findAll();
        // Group instances per activity (ignore those whose activity got purged).
        Map<ObjectId, List<ActivityInstance>> byActivity = all.stream()
                .filter(i -> i.getActividadId() != null
                        && activityById.containsKey(i.getActividadId()))
                .collect(Collectors.groupingBy(ActivityInstance::getActividadId));

        List<BottleneckActivityDTO> out = new ArrayList<>();
        for (Map.Entry<ObjectId, List<ActivityInstance>> e : byActivity.entrySet()) {
            Activity activity = activityById.get(e.getKey());
            List<ActivityInstance> insts = e.getValue();

            double avgWait = insts.stream()
                    .filter(i -> STATE_COMPLETED.equals(i.getEstado()))
                    .map(this::waitMinutes).filter(d -> d >= 0)
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            double avgService = insts.stream()
                    .filter(i -> STATE_COMPLETED.equals(i.getEstado()))
                    .map(this::serviceMinutes).filter(d -> d >= 0)
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            double avgLead = insts.stream()
                    .filter(i -> STATE_COMPLETED.equals(i.getEstado()))
                    .map(this::leadMinutes).filter(d -> d >= 0)
                    .mapToDouble(Double::doubleValue).average().orElse(0);
            long backlog = insts.stream()
                    .filter(i -> STATE_WAITING.equals(i.getEstado())
                            || STATE_BLOCKED.equals(i.getEstado()))
                    .count();
            long completed = insts.stream()
                    .filter(i -> STATE_COMPLETED.equals(i.getEstado())).count();

            BusinessPolicy policy = activity.getPoliticaId() == null
                    ? null
                    : policyById.get(activity.getPoliticaId());
            Lane lane = activity.getCalleId() == null
                    ? null
                    : laneById.get(activity.getCalleId());

            out.add(BottleneckActivityDTO.builder()
                    .activityId(activity.getId().toHexString())
                    .activityName(activity.getNombre())
                    .policyName(policy == null ? null : policy.getNombre())
                    .laneName(lane == null ? null : lane.getNombre())
                    .avgWaitMinutes(round(avgWait))
                    .avgServiceMinutes(round(avgService))
                    .avgLeadMinutes(round(avgLead))
                    .currentBacklog(backlog)
                    .completedCount(completed)
                    .build());
        }
        // Sort by lead-time descending (slowest first → most likely bottleneck).
        out.sort(Comparator.comparingDouble(BottleneckActivityDTO::getAvgLeadMinutes).reversed());
        return out;
    }

    // ── Operator performance ──────────────────────────────────────────────

    public List<OperatorPerformanceDTO> getOperatorPerformance() {
        List<ActivityInstance> all = activityInstanceRepository.findAll();
        // Aggregate per operator (claimedBy).
        Map<ObjectId, List<ActivityInstance>> byOperator = all.stream()
                .filter(i -> i.getClaimedBy() != null)
                .collect(Collectors.groupingBy(ActivityInstance::getClaimedBy));
        if (byOperator.isEmpty()) return List.of();

        // Median service time across the whole population for fairness.
        List<Double> allServiceTimes = all.stream()
                .filter(i -> STATE_COMPLETED.equals(i.getEstado()))
                .map(this::serviceMinutes).filter(d -> d >= 0)
                .sorted().toList();
        double teamMedian = median(allServiceTimes);

        Map<ObjectId, User> userById = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        List<OperatorPerformanceDTO> out = new ArrayList<>();
        for (Map.Entry<ObjectId, List<ActivityInstance>> e : byOperator.entrySet()) {
            User user = userById.get(e.getKey());
            List<ActivityInstance> insts = e.getValue();

            long completed = insts.stream()
                    .filter(i -> STATE_COMPLETED.equals(i.getEstado())).count();
            long inProgress = insts.stream()
                    .filter(i -> STATE_IN_PROGRESS.equals(i.getEstado())).count();
            double avgService = insts.stream()
                    .filter(i -> STATE_COMPLETED.equals(i.getEstado()))
                    .map(this::serviceMinutes).filter(d -> d >= 0)
                    .mapToDouble(Double::doubleValue).average().orElse(0);

            out.add(OperatorPerformanceDTO.builder()
                    .userId(e.getKey().toHexString())
                    .fullName(user == null ? null : user.getNombre())
                    .email(user == null ? null : user.getEmail())
                    .completedCount(completed)
                    .inProgressCount(inProgress)
                    .avgServiceMinutes(round(avgService))
                    .teamMedianServiceMinutes(round(teamMedian))
                    .build());
        }
        // Sort by completed desc so the most active appear first.
        out.sort(Comparator.comparingLong(OperatorPerformanceDTO::getCompletedCount).reversed());
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private double waitMinutes(ActivityInstance i) {
        if (i.getCreatedAt() == null || i.getFechaInicio() == null) return -1;
        return minutesBetween(i.getCreatedAt(), i.getFechaInicio());
    }

    private double serviceMinutes(ActivityInstance i) {
        if (i.getFechaInicio() == null || i.getFechaFin() == null) return -1;
        return minutesBetween(i.getFechaInicio(), i.getFechaFin());
    }

    private double leadMinutes(ActivityInstance i) {
        LocalDateTime start = i.getCreatedAt() != null ? i.getCreatedAt() : i.getFechaInicio();
        if (start == null || i.getFechaFin() == null) return -1;
        return minutesBetween(start, i.getFechaFin());
    }

    private double minutesBetween(LocalDateTime a, LocalDateTime b) {
        return Duration.between(a, b).toSeconds() / 60.0;
    }

    private double percentileLeadMinutes(List<ActivityInstance> instances, int percentile) {
        List<Double> sorted = instances.stream()
                .filter(i -> STATE_COMPLETED.equals(i.getEstado()))
                .map(this::leadMinutes).filter(d -> d >= 0)
                .sorted().toList();
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return round(sorted.get(idx));
    }

    private double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
