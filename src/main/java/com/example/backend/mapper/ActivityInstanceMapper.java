package com.example.backend.mapper;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ActivityInstanceMapper {

    public ActivityInstanceResponseDTO toResponse(ActivityInstance instance) {
        String claimedBy = instance.getClaimedBy() != null
                ? instance.getClaimedBy().toHexString()
                : null;
        return ActivityInstanceResponseDTO.builder()
                .id(instance.getId().toHexString())
                .caseFileId(instance.getTramiteId() != null
                        ? instance.getTramiteId().toHexString() : null)
                .activityId(instance.getActividadId() != null
                        ? instance.getActividadId().toHexString() : null)
                .status(normalizeStatus(instance.getEstado()))
                .assignedUserIds(hexList(instance.getAssignedUserIds()))
                .claimedBy(claimedBy)
                // Mirror claimedBy as the singular field so older clients keep working.
                .assignedUserId(claimedBy)
                .createdAt(instance.getCreatedAt())
                .startedAt(instance.getFechaInicio())
                .finishedAt(instance.getFechaFin())
                .build();
    }

    public ActivityInstanceResponseDTO toResponse(ActivityInstance instance, Activity activity) {
        ActivityInstanceResponseDTO dto = toResponse(instance);
        if (activity != null) {
            dto.setActivityName(activity.getNombre());
            dto.setActivityType(activity.getTipo());
        }
        return dto;
    }

    private List<String> hexList(List<ObjectId> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return source.stream()
                .filter(java.util.Objects::nonNull)
                .map(ObjectId::toHexString)
                .toList();
    }

    /** Backend stores Spanish codes; external callers expect English enum labels. */
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
