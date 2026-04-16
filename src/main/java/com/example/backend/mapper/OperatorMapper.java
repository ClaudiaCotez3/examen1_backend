package com.example.backend.mapper;

import com.example.backend.dto.OperatorTaskDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.Lane;
import com.example.backend.model.Procedure;
import org.springframework.stereotype.Component;

/**
 * Builds OperatorTaskDTO from pre-loaded Activity, Lane and Procedure references.
 * Caller is expected to batch-load these (single query each) to avoid N+1.
 */
@Component
public class OperatorMapper {

    public OperatorTaskDTO toDto(ActivityInstance instance, Activity activity, Lane lane, Procedure caseFile) {
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
                .assignedUserId(instance.getAsignadoA() != null ? instance.getAsignadoA().toHexString() : null)
                .createdAt(caseFile != null ? caseFile.getFechaInicio() : null)
                .startedAt(instance.getFechaInicio())
                .finishedAt(instance.getFechaFin())
                .build();
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
