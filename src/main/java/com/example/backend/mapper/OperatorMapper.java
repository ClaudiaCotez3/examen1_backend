package com.example.backend.mapper;

import com.example.backend.dto.OperatorTaskDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.Lane;
import com.example.backend.model.Procedure;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Builds {@link OperatorTaskDTO} from pre-loaded Activity, Lane, Procedure
 * and claimer-name references. Callers batch these lookups to avoid N+1.
 */
@Component
public class OperatorMapper {

    public OperatorTaskDTO toDto(
            ActivityInstance instance,
            Activity activity,
            Lane lane,
            Procedure caseFile,
            String claimerName) {

        String claimedBy = instance.getClaimedBy() != null
                ? instance.getClaimedBy().toHexString()
                : null;

        return OperatorTaskDTO.builder()
                .activityInstanceId(instance.getId().toHexString())
                .activityId(instance.getActividadId() != null ? instance.getActividadId().toHexString() : null)
                .activityName(activity != null ? activity.getNombre() : null)
                .activityType(activity != null ? activity.getTipo() : null)
                .status(normalizeStatus(instance.getEstado()))
                .caseFileId(instance.getTramiteId() != null ? instance.getTramiteId().toHexString() : null)
                .caseFileCode(caseFile != null ? caseFile.getCodigo() : null)
                .laneId(lane != null ? lane.getId().toHexString() : null)
                .laneName(lane != null ? lane.getNombre() : null)
                .assignedUserIds(hexList(instance.getAssignedUserIds()))
                .claimedBy(claimedBy)
                // Singular mirror for frontends that still read `assignedUserId`.
                .assignedUserId(claimedBy)
                .assignedUserName(claimerName)
                .createdAt(instance.getCreatedAt() != null
                        ? instance.getCreatedAt()
                        : (caseFile != null ? caseFile.getFechaInicio() : null))
                .startedAt(instance.getFechaInicio())
                .finishedAt(instance.getFechaFin())
                .build();
    }

    /** 4-arg overload kept for callers that don't pre-load the claimer name. */
    public OperatorTaskDTO toDto(ActivityInstance instance, Activity activity, Lane lane, Procedure caseFile) {
        return toDto(instance, activity, lane, caseFile, null);
    }

    private List<String> hexList(List<ObjectId> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return source.stream()
                .filter(java.util.Objects::nonNull)
                .map(ObjectId::toHexString)
                .toList();
    }

    /** Backend stores Spanish codes; the operator UI expects uppercase English. */
    private String normalizeStatus(String estado) {
        if (estado == null) return null;
        return switch (estado) {
            case "en_espera" -> "WAITING";
            case "en_proceso" -> "IN_PROGRESS";
            case "finalizado" -> "COMPLETED";
            default -> estado;
        };
    }
}
