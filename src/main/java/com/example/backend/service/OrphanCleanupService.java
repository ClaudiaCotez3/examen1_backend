package com.example.backend.service;

import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Flow;
import com.example.backend.model.Lane;
import com.example.backend.model.PolicyVersion;
import com.example.backend.model.Procedure;
import com.example.backend.model.ProcedureHistory;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.FlowRepository;
import com.example.backend.repository.LaneRepository;
import com.example.backend.repository.PolicyVersionRepository;
import com.example.backend.repository.ProcedureHistoryRepository;
import com.example.backend.repository.ProcedureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cleans up runtime documents that no longer have a definition backing them.
 *
 * Runs automatically once at application start so the Mongo dataset always
 * boots in a self-consistent shape — old environments where deletePolicy
 * used to be a logical-only flip leave behind ActivityInstance and
 * ProcedureHistory rows whose Activity / Procedure have been removed since.
 * The same logic is exposed via {@code POST /api/debug/purge-orphans} so it
 * can be triggered on demand without restarting.
 *
 * What we consider orphan:
 *   - ActivityInstance whose actividad_id no longer maps to a real Activity.
 *   - ProcedureHistory whose tramite_id no longer maps to a real Procedure.
 *   - Procedure whose version_politica_id no longer maps to a real PolicyVersion.
 *     When a Procedure is removed we cascade its instances + history so we
 *     don't promote them into orphans on the next pass.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrphanCleanupService {

    private final ActivityRepository activityRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final ProcedureRepository procedureRepository;
    private final ProcedureHistoryRepository procedureHistoryRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final BusinessPolicyRepository businessPolicyRepository;
    private final LaneRepository laneRepository;
    private final FlowRepository flowRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanOnStartup() {
        try {
            CleanupStats stats = purge();
            if (stats.totalRemoved() > 0) {
                log.info("Orphan cleanup on startup: {}", stats);
            } else {
                log.info("Orphan cleanup on startup: nothing to remove");
            }
        } catch (RuntimeException ex) {
            // Don't crash the app over a cleanup hiccup — log and move on.
            log.warn("Orphan cleanup on startup failed: {}", ex.getMessage());
        }
    }

    /**
     * Walks the dependency graph top-down (policy → version → procedure →
     * instance/history; policy → lane/activity/flow) and removes every
     * row whose parent has already been deleted. Order matters: cleaning
     * the top first promotes downstream documents to orphans of the next
     * pass, which is exactly what we want.
     *
     * Step 0 also folds the legacy "logical delete" path into a real
     * removal: BusinessPolicies sitting on {@code estado = ARCHIVED}
     * (created before the cascade hard-delete switch) get hard-deleted
     * here, which makes every one of their children orphan and lets the
     * downstream sweeps below clean them up in a single pass.
     */
    public CleanupStats purge() {
        // 0. Collapse logical-archived policies into hard deletes so their
        //    activities / lanes / versions / procedures all become orphans
        //    that the rest of this routine can sweep away.
        List<BusinessPolicy> archivedPolicies = businessPolicyRepository.findAll().stream()
                .filter(p -> "ARCHIVED".equalsIgnoreCase(p.getEstado()))
                .toList();
        if (!archivedPolicies.isEmpty()) {
            businessPolicyRepository.deleteAll(archivedPolicies);
        }

        Set<ObjectId> policyIds = businessPolicyRepository.findAll().stream()
                .map(BusinessPolicy::getId)
                .collect(Collectors.toSet());

        // Lanes / Activities / Flows / PolicyVersions whose policy is gone.
        List<Lane> orphanLanes = laneRepository.findAll().stream()
                .filter(l -> l.getPoliticaId() == null || !policyIds.contains(l.getPoliticaId()))
                .toList();
        if (!orphanLanes.isEmpty()) laneRepository.deleteAll(orphanLanes);

        List<Activity> orphanActivities = activityRepository.findAll().stream()
                .filter(a -> a.getPoliticaId() == null || !policyIds.contains(a.getPoliticaId()))
                .toList();
        if (!orphanActivities.isEmpty()) activityRepository.deleteAll(orphanActivities);

        List<PolicyVersion> orphanVersions = policyVersionRepository.findAll().stream()
                .filter(v -> v.getPoliticaId() == null || !policyIds.contains(v.getPoliticaId()))
                .toList();
        if (!orphanVersions.isEmpty()) policyVersionRepository.deleteAll(orphanVersions);

        // Flows referencing activities that no longer exist (recompute the
        // activity set after the orphan-activity deletion above).
        Set<ObjectId> activityIds = activityRepository.findAll().stream()
                .map(Activity::getId)
                .collect(Collectors.toSet());
        List<Flow> orphanFlows = flowRepository.findAll().stream()
                .filter(f -> (f.getActividadOrigenId() != null && !activityIds.contains(f.getActividadOrigenId()))
                        || (f.getActividadDestinoId() != null && !activityIds.contains(f.getActividadDestinoId())))
                .toList();
        if (!orphanFlows.isEmpty()) flowRepository.deleteAll(orphanFlows);

        // Procedures whose version is gone (now that orphan versions were
        // deleted, this catches both pre-existing orphans AND the ones we
        // just promoted).
        Set<ObjectId> versionIds = policyVersionRepository.findAll().stream()
                .map(PolicyVersion::getId)
                .collect(Collectors.toSet());
        List<Procedure> orphanProcedures = procedureRepository.findAll().stream()
                .filter(p -> p.getVersionPoliticaId() == null
                        || !versionIds.contains(p.getVersionPoliticaId()))
                .toList();
        if (!orphanProcedures.isEmpty()) {
            for (Procedure p : orphanProcedures) {
                List<ActivityInstance> ins = activityInstanceRepository.findByTramiteId(p.getId());
                if (!ins.isEmpty()) activityInstanceRepository.deleteAll(ins);
                List<ProcedureHistory> hs = procedureHistoryRepository
                        .findByTramiteIdOrderByFechaAsc(p.getId());
                if (!hs.isEmpty()) procedureHistoryRepository.deleteAll(hs);
            }
            procedureRepository.deleteAll(orphanProcedures);
        }

        // Final sweep: any ActivityInstance / ProcedureHistory pointing
        // to documents that no longer exist (covers cases where the
        // procedure cascade above already wiped its children but legacy
        // rows from earlier deletes are still around).
        Set<ObjectId> liveActivityIds = activityRepository.findAll().stream()
                .map(Activity::getId)
                .collect(Collectors.toSet());
        List<ActivityInstance> orphanInstances = activityInstanceRepository.findAll().stream()
                .filter(i -> i.getActividadId() == null
                        || !liveActivityIds.contains(i.getActividadId()))
                .toList();
        if (!orphanInstances.isEmpty()) activityInstanceRepository.deleteAll(orphanInstances);

        Set<ObjectId> liveProcedureIds = procedureRepository.findAll().stream()
                .map(Procedure::getId)
                .collect(Collectors.toSet());
        List<ProcedureHistory> orphanHistory = procedureHistoryRepository.findAll().stream()
                .filter(h -> h.getTramiteId() == null
                        || !liveProcedureIds.contains(h.getTramiteId()))
                .toList();
        if (!orphanHistory.isEmpty()) procedureHistoryRepository.deleteAll(orphanHistory);

        return new CleanupStats(
                orphanInstances.size(),
                orphanHistory.size(),
                orphanProcedures.size(),
                orphanVersions.size(),
                orphanActivities.size(),
                orphanLanes.size(),
                orphanFlows.size(),
                archivedPolicies.size()
        );
    }

    public record CleanupStats(
            int instances,
            int history,
            int procedures,
            int versions,
            int activities,
            int lanes,
            int flows,
            int archivedPolicies
    ) {
        public int totalRemoved() {
            return instances + history + procedures + versions
                    + activities + lanes + flows + archivedPolicies;
        }
    }
}
