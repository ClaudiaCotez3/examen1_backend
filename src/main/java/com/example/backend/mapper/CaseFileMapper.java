package com.example.backend.mapper;

import com.example.backend.dto.CaseFileResponseDTO;
import com.example.backend.model.Procedure;
import org.springframework.stereotype.Component;

@Component
public class CaseFileMapper {

    public CaseFileResponseDTO toResponse(Procedure procedure) {
        return CaseFileResponseDTO.builder()
                .id(procedure.getId().toHexString())
                .code(procedure.getCodigo())
                .policyVersionId(procedure.getVersionPoliticaId() != null
                        ? procedure.getVersionPoliticaId().toHexString() : null)
                .status(procedure.getEstado())
                .createdAt(procedure.getFechaInicio())
                .finishedAt(procedure.getFechaFin())
                .build();
    }
}
