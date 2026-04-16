package com.example.backend.mapper;

import com.example.backend.dto.ProcessHistoryResponseDTO;
import com.example.backend.model.Activity;
import com.example.backend.model.ProcedureHistory;
import org.springframework.stereotype.Component;

@Component
public class ProcessHistoryMapper {

    public ProcessHistoryResponseDTO toResponse(ProcedureHistory history) {
        return ProcessHistoryResponseDTO.builder()
                .id(history.getId().toHexString())
                .caseFileId(history.getTramiteId() != null
                        ? history.getTramiteId().toHexString() : null)
                .activityId(history.getActividadId() != null
                        ? history.getActividadId().toHexString() : null)
                .action(history.getAccion())
                .userId(history.getUsuarioId() != null
                        ? history.getUsuarioId().toHexString() : null)
                .timestamp(history.getFecha())
                .build();
    }

    public ProcessHistoryResponseDTO toResponse(ProcedureHistory history, Activity activity) {
        ProcessHistoryResponseDTO dto = toResponse(history);
        if (activity != null) {
            dto.setActivityName(activity.getNombre());
        }
        return dto;
    }
}
