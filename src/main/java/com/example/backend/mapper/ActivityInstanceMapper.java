package com.example.backend.mapper;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import org.springframework.stereotype.Component;

@Component
public class ActivityInstanceMapper {

    public ActivityInstanceResponseDTO toResponse(ActivityInstance instance) {
        return ActivityInstanceResponseDTO.builder()
                .id(instance.getId().toHexString())
                .caseFileId(instance.getTramiteId() != null
                        ? instance.getTramiteId().toHexString() : null)
                .activityId(instance.getActividadId() != null
                        ? instance.getActividadId().toHexString() : null)
                .status(instance.getEstado())
                .assignedUserId(instance.getAsignadoA() != null
                        ? instance.getAsignadoA().toHexString() : null)
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
}
