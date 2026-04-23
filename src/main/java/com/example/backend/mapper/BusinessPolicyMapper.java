package com.example.backend.mapper;

import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.BusinessPolicyResponseDTO;
import com.example.backend.model.BusinessPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BusinessPolicyMapper {

    public BusinessPolicy toEntity(BusinessPolicyRequestDTO dto) {
        return BusinessPolicy.builder()
                .nombre(dto.getName())
                .descripcion(dto.getDescription())
                .bpmnXml(dto.getBpmnXml())
                .prerequisitos(copyOrNull(dto.getPrerequisites()))
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
                .prerequisites(copyOrEmpty(policy.getPrerequisitos()))
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
        // Same reasoning for prerequisites: a plain metadata PATCH should not
        // silently erase them. Only overwrite when the caller explicitly
        // provided a list (even an empty one).
        if (dto.getPrerequisites() != null) {
            policy.setPrerequisitos(new ArrayList<>(dto.getPrerequisites()));
        }
    }

    private List<String> copyOrNull(List<String> source) {
        return source == null ? null : new ArrayList<>(source);
    }

    private List<String> copyOrEmpty(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }
}
