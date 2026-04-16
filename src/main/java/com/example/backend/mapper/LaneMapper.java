package com.example.backend.mapper;

import com.example.backend.dto.LaneRequestDTO;
import com.example.backend.dto.LaneResponseDTO;
import com.example.backend.model.Lane;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

@Component
public class LaneMapper {

    public Lane toEntity(LaneRequestDTO dto, ObjectId policyId) {
        return Lane.builder()
                .politicaId(policyId)
                .nombre(dto.getName())
                .posicion(dto.getPosition())
                .build();
    }

    public LaneResponseDTO toResponse(Lane lane) {
        return LaneResponseDTO.builder()
                .id(lane.getId() != null ? lane.getId().toHexString() : null)
                .policyId(lane.getPoliticaId() != null ? lane.getPoliticaId().toHexString() : null)
                .name(lane.getNombre())
                .position(lane.getPosicion())
                .build();
    }
}
