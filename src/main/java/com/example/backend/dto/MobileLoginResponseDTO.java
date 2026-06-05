package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Portal móvil — sesión del cliente + sus trámites en una sola llamada. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobileLoginResponseDTO {

    private String customerId;
    private String name;
    private String email;
    private String ci;

    /** Trámites del cliente con su progreso (mismo shape que Consultas). */
    private List<ConsultationCaseDTO> cases;
}
