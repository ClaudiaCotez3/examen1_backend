package com.example.backend.mapper;

import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.BusinessPolicyResponseDTO;
import com.example.backend.model.BusinessPolicy;
import org.springframework.stereotype.Component;

@Component
public class BusinessPolicyMapper {

    public BusinessPolicy toEntity(BusinessPolicyRequestDTO dto) {
        return BusinessPolicy.builder()
                .nombre(dto.getName())
                .descripcion(dto.getDescription())
                .bpmnXml(dto.getBpmnXml())
                .build();
    }

    public BusinessPolicyResponseDTO toResponse(BusinessPolicy policy) {
        return BusinessPolicyResponseDTO.builder()
                .id(policy.getId() != null ? policy.getId().toHexString() : null)
                .name(policy.getNombre())
                .description(policy.getDescripcion())
                .status(policy.getEstado())
                .bpmnXml(policy.getBpmnXml())
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
    }
}
