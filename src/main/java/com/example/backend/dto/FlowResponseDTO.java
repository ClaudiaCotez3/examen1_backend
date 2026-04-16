package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowResponseDTO {

    private String id;
    private String sourceActivityId;
    private String targetActivityId;
    private String type;
    private String condition;
}
