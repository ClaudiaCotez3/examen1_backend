package com.example.backend.mapper;

import com.example.backend.dto.FlowRequestDTO;
import com.example.backend.dto.FlowResponseDTO;
import com.example.backend.model.Flow;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

@Component
public class FlowMapper {

    public Flow toEntity(FlowRequestDTO dto, ObjectId sourceActivityId, ObjectId targetActivityId) {
        return Flow.builder()
                .actividadOrigenId(sourceActivityId)
                .actividadDestinoId(targetActivityId)
                .tipo(dto.getType())
                .condicion(dto.getCondition())
                .build();
    }

    public FlowResponseDTO toResponse(Flow flow) {
        return FlowResponseDTO.builder()
                .id(flow.getId() != null ? flow.getId().toHexString() : null)
                .sourceActivityId(flow.getActividadOrigenId() != null ? flow.getActividadOrigenId().toHexString() : null)
                .targetActivityId(flow.getActividadDestinoId() != null ? flow.getActividadDestinoId().toHexString() : null)
                .type(flow.getTipo())
                .condition(flow.getCondicion())
                .build();
    }
}
