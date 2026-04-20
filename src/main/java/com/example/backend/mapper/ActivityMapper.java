package com.example.backend.mapper;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.ActivityResponseDTO;
import com.example.backend.model.Activity;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ActivityMapper {

    private final FormMapper formMapper;

    public Activity toEntity(ActivityRequestDTO dto, ObjectId policyId, ObjectId laneId) {
        return Activity.builder()
                .politicaId(policyId)
                .calleId(laneId)
                .nombre(dto.getName())
                .tipo(dto.getType())
                .requiereFormulario(Boolean.TRUE.equals(dto.getRequiresForm()))
                .formDefinition(formMapper.toEntity(dto.getFormDefinition()))
                .assignedUserIds(resolveAssignees(dto))
                .requirements(copyOrEmpty(dto.getRequirements()))
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
                .formDefinition(formMapper.toDefinitionDTO(activity.getFormDefinition()))
                .assignedUserIds(copyOrEmpty(activity.getAssignedUserIds()))
                .requirements(copyOrEmpty(activity.getRequirements()))
                .build();
    }

    /**
     * Coalesces the singular `assignedUserId` and plural `assignedUserIds`
     * fields into a single list, preferring the plural and de-duplicating.
     * Lets older clients that still send only the singular field keep working.
     */
    private List<String> resolveAssignees(ActivityRequestDTO dto) {
        List<String> plural = dto.getAssignedUserIds();
        if (plural != null && !plural.isEmpty()) {
            return new ArrayList<>(plural);
        }
        if (dto.getAssignedUserId() != null && !dto.getAssignedUserId().isBlank()) {
            return new ArrayList<>(List.of(dto.getAssignedUserId()));
        }
        return new ArrayList<>();
    }

    private List<String> copyOrEmpty(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }
}
