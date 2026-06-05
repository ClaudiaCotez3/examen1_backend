package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Portal móvil — credenciales del cliente. Un cliente que ya abrió un
 * trámite en atención al cliente existe como {@link com.example.backend.model.Customer}
 * (Opción B), así que se identifica con el par correo + CI capturado en
 * su formulario inicial.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobileLoginRequestDTO {

    @NotBlank(message = "email is required")
    private String email;

    @NotBlank(message = "ci is required")
    private String ci;
}
