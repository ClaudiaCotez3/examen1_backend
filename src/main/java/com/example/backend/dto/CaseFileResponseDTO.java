package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseFileResponseDTO {

    private String id;
    private String code;
    private String policyVersionId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private List<ActivityInstanceResponseDTO> currentActivities;
}
