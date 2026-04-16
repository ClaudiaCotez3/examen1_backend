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
public class PolicyVersionResponseDTO {

    private String id;
    private String policyId;
    private Integer versionNumber;
    private Boolean active;
    private LocalDateTime createdAt;
}
