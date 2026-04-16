package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessHistoryResponseDTO {

    private String id;
    private String caseFileId;
    private String activityId;
    private String activityName;
    private String action;
    private String userId;
    private LocalDateTime timestamp;
}
