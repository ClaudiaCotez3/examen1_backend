package com.example.backend.mapper;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.ActivityResponseDTO;
import com.example.backend.model.Activity;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

@Component
public class ActivityMapper {

    public Activity toEntity(ActivityRequestDTO dto, ObjectId policyId, ObjectId laneId) {
        return Activity.builder()
                .politicaId(policyId)
                .calleId(laneId)
                .nombre(dto.getName())
                .tipo(dto.getType())
                .requiereFormulario(Boolean.TRUE.equals(dto.getRequiresForm()))
                .build();
    }

    public ActivityResponseDTO toResponse(Activity activity) {
        return ActivityResponseDTO.builder()
                .id(activity.getId() != null ? activity.getId().toHexString() : null)
                .policyId(activity.getPoliticaId() != null ? activity.getPoliticaId().toHexString() : null)
                .laneId(activity.getCalleId() != null ? activity.getCalleId().toHexString() : null)
                .name(activity.getNombre())
                .type(activity.getTipo())
                .requiresForm(activity.getRequiereFormulario())
                .build();
    }
}
