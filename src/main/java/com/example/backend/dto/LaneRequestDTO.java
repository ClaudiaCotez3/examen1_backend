package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LaneRequestDTO {

    /** Temporary client-side id used to reference this lane inside a full policy payload. */
    private String clientId;

    @NotBlank(message = "Lane name is required")
    private String name;

    @NotNull(message = "Lane position is required")
    @PositiveOrZero(message = "Lane position must be zero or positive")
    private Integer position;
}
