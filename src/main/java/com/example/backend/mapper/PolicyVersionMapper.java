package com.example.backend.mapper;

import com.example.backend.dto.PolicyVersionResponseDTO;
import com.example.backend.model.PolicyVersion;
import org.springframework.stereotype.Component;

@Component
public class PolicyVersionMapper {

    public PolicyVersionResponseDTO toResponse(PolicyVersion version) {
        return PolicyVersionResponseDTO.builder()
                .id(version.getId() != null ? version.getId().toHexString() : null)
                .policyId(version.getPoliticaId() != null ? version.getPoliticaId().toHexString() : null)
                .versionNumber(version.getNumeroVersion())
                .active("ACTIVE".equals(version.getEstado()))
                .createdAt(version.getFechaPublicacion())
                .build();
    }
}
