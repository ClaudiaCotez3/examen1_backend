package com.example.backend.mapper;

import com.example.backend.dto.ActivityFormDTO;
import com.example.backend.dto.FormDefinitionDTO;
import com.example.backend.dto.FormFieldDTO;
import com.example.backend.dto.FormResponseDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.FormFieldDefinition;
import com.example.backend.model.FormResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Translates between {@link FormDefinition}/{@link FormResponse} entities and
 * the API DTOs.
 *
 * Kept stateless and free of business logic on purpose — validation and
 * security checks live in {@link com.example.backend.service.FormService}.
 */
@Component
public class FormMapper {

    // ── Definition ──────────────────────────────────────────────────────────

    public FormDefinition toEntity(FormDefinitionDTO dto) {
        if (dto == null) {
            return null;
        }
        List<FormFieldDefinition> fields = dto.getFields() == null ? List.of()
                : dto.getFields().stream().map(this::toFieldEntity).toList();
        return FormDefinition.builder().fields(fields).build();
    }

    public FormDefinitionDTO toDefinitionDTO(FormDefinition entity) {
        if (entity == null) {
            return null;
        }
        List<FormFieldDTO> fields = entity.getFields() == null ? List.of()
                : entity.getFields().stream().map(this::toFieldDTO).toList();
        return FormDefinitionDTO.builder().fields(fields).build();
    }

    private FormFieldDefinition toFieldEntity(FormFieldDTO dto) {
        return FormFieldDefinition.builder()
                .name(dto.getName())
                .label(dto.getLabel())
                .type(dto.getType())
                .required(Boolean.TRUE.equals(dto.getRequired()))
                .options(dto.getOptions())
                .build();
    }

    private FormFieldDTO toFieldDTO(FormFieldDefinition entity) {
        return FormFieldDTO.builder()
                .name(entity.getName())
                .label(entity.getLabel())
                .type(entity.getType())
                .required(entity.getRequired())
                .options(entity.getOptions())
                .build();
    }

    // ── Composite views ─────────────────────────────────────────────────────

    public ActivityFormDTO toActivityForm(Activity activity) {
        return ActivityFormDTO.builder()
                .activityId(activity.getId() != null ? activity.getId().toHexString() : null)
                .activityName(activity.getNombre())
                .requiresForm(activity.getRequiereFormulario())
                .formDefinition(toDefinitionDTO(activity.getFormDefinition()))
                .build();
    }

    // ── Submission ──────────────────────────────────────────────────────────

    public FormResponseDTO toResponseDTO(FormResponse entity) {
        return FormResponseDTO.builder()
                .id(entity.getId() != null ? entity.getId().toHexString() : null)
                .activityInstanceId(entity.getInstanciaActividadId() != null
                        ? entity.getInstanciaActividadId().toHexString() : null)
                .formData(entity.getData())
                .submittedBy(entity.getSubmittedBy() != null
                        ? entity.getSubmittedBy().toHexString() : null)
                .submittedAt(entity.getSubmittedAt())
                .build();
    }
}
