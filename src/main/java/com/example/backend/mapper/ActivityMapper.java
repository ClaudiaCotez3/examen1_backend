package com.example.backend.mapper;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.ActivityResponseDTO;
import com.example.backend.model.Activity;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ActivityMapper {

    private static final Set<String> ASSIGNMENT_TYPES = Set.of(
            "USER", "CANDIDATE_USERS", "DEPARTMENT"
    );
    private static final String DEFAULT_ASSIGNMENT_TYPE = "DEPARTMENT";

    private final FormMapper formMapper;

    public Activity toEntity(ActivityRequestDTO dto, ObjectId policyId, ObjectId laneId) {
        boolean hasForm = dto.getFormDefinition() != null || !isBlank(dto.getFormId());
        return Activity.builder()
                .politicaId(policyId)
                .calleId(laneId)
                .nombre(dto.getName())
                .tipo(dto.getType())
                .assignmentType(resolveAssignmentType(dto.getType(), dto.getAssignmentType()))
                // requiresForm is inferred — never trust the DTO field blindly.
                .requiereFormulario(hasForm)
                .formId(parseObjectIdOrNull(dto.getFormId()))
                .formDefinition(formMapper.toEntity(dto.getFormDefinition()))
                .assignedUserIds(resolveAssignees(dto))
                .build();
    }

    public ActivityResponseDTO toResponse(Activity activity) {
        return ActivityResponseDTO.builder()
                .id(activity.getId() != null ? activity.getId().toHexString() : null)
                .policyId(activity.getPoliticaId() != null ? activity.getPoliticaId().toHexString() : null)
                .laneId(activity.getCalleId() != null ? activity.getCalleId().toHexString() : null)
                .name(activity.getNombre())
                .type(activity.getTipo())
                .assignmentType(activity.getAssignmentType())
                .requiresForm(activity.getRequiereFormulario())
                .formId(activity.getFormId() != null ? activity.getFormId().toHexString() : null)
                .formDefinition(formMapper.toDefinitionDTO(activity.getFormDefinition()))
                .assignedUserIds(copyOrEmpty(activity.getAssignedUserIds()))
                .build();
    }

    /**
     * Coalesces the singular and plural assignee fields. Older clients can
     * still send a single id; newer ones send the list. De-duplicated while
     * preserving order.
     */
    private List<String> resolveAssignees(ActivityRequestDTO dto) {
        List<String> plural = dto.getAssignedUserIds();
        if (plural != null && !plural.isEmpty()) {
            return new ArrayList<>(plural);
        }
        if (!isBlank(dto.getAssignedUserId())) {
            return new ArrayList<>(List.of(dto.getAssignedUserId()));
        }
        return new ArrayList<>();
    }

    /**
     * Normalizes the assignment type. Only tasks carry it — other node types
     * get null. Unknown values fall back to the default (DEPARTMENT) so a
     * misbehaving client can't persist an invalid enum.
     */
    private String resolveAssignmentType(String activityType, String raw) {
        if (!"TASK".equals(activityType)) return null;
        if (isBlank(raw)) return DEFAULT_ASSIGNMENT_TYPE;
        String trimmed = raw.trim();
        return ASSIGNMENT_TYPES.contains(trimmed) ? trimmed : DEFAULT_ASSIGNMENT_TYPE;
    }

    private ObjectId parseObjectIdOrNull(String raw) {
        if (isBlank(raw)) return null;
        return ObjectId.isValid(raw) ? new ObjectId(raw) : null;
    }

    private List<String> copyOrEmpty(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
