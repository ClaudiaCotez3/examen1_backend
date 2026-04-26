package com.example.backend.mapper;

import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.BusinessPolicyResponseDTO;
import com.example.backend.model.BusinessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BusinessPolicyMapper {

    private final FormMapper formMapper;

    public BusinessPolicy toEntity(BusinessPolicyRequestDTO dto) {
        return BusinessPolicy.builder()
                .nombre(dto.getName())
                .descripcion(dto.getDescription())
                .bpmnXml(dto.getBpmnXml())
                .startFormDefinition(formMapper.toEntity(dto.getStartFormDefinition()))
                .startFormSchema(copyMapOrNull(dto.getStartFormSchema()))
                .build();
    }

    public BusinessPolicyResponseDTO toResponse(BusinessPolicy policy) {
        return BusinessPolicyResponseDTO.builder()
                .id(policy.getId() != null ? policy.getId().toHexString() : null)
                .name(policy.getNombre())
                .description(policy.getDescripcion())
                .status(policy.getEstado())
                .version(policy.getVersion())
                .bpmnXml(policy.getBpmnXml())
                .startFormDefinition(formMapper.toDefinitionDTO(policy.getStartFormDefinition()))
                .startFormSchema(copyMapOrNull(policy.getStartFormSchema()))
                .createdAt(policy.getFechaCreacion())
                .updatedAt(policy.getFechaActualizacion())
                .build();
    }

    public void updateEntity(BusinessPolicy policy, BusinessPolicyRequestDTO dto) {
        policy.setNombre(dto.getName());
        policy.setDescripcion(dto.getDescription());
        // Only overwrite the stored XML when the caller actually provided one,
        // so plain field updates don't accidentally wipe the diagram.
        if (dto.getBpmnXml() != null) {
            policy.setBpmnXml(dto.getBpmnXml());
        }
        // Start form fields: treat "null" from the DTO as "don't touch" so a
        // metadata-only PATCH doesn't erase a previously configured start
        // form. To explicitly clear the form, callers can send an empty
        // FormDefinitionDTO (fields: []) instead of omitting the key.
        if (dto.getStartFormDefinition() != null) {
            policy.setStartFormDefinition(formMapper.toEntity(dto.getStartFormDefinition()));
        }
        if (dto.getStartFormSchema() != null) {
            policy.setStartFormSchema(copyMapOrNull(dto.getStartFormSchema()));
        }
    }

    private Map<String, Object> copyMapOrNull(Map<String, Object> source) {
        return source == null ? null : new HashMap<>(source);
    }
}
