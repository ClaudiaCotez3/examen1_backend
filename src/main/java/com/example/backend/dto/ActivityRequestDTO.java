package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityRequestDTO {

    /** Temporary client-side id used as source/target reference in flows during full-policy save. */
    private String clientId;

    @NotBlank(message = "Activity name is required")
    private String name;

    /** START | TASK | DECISION | END */
    @NotBlank(message = "Activity type is required")
    private String type;

    /** When sent inside a full-policy payload, points to LaneRequestDTO.clientId. */
    @NotBlank(message = "Lane reference is required")
    private String laneRef;

    private Boolean requiresForm;
}
