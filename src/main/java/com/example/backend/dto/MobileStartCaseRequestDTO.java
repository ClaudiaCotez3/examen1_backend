package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Portal móvil — Módulo 3: el cliente inicia un trámite desde la app.
 * Las credenciales viajan en cada request (modelo credential-per-request
 * del portal); los campos reservados cliente_* del formulario se
 * sobreescriben server-side con la identidad VERIFICADA del cliente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobileStartCaseRequestDTO {

    @NotBlank(message = "email is required")
    private String email;

    @NotBlank(message = "ci is required")
    private String ci;

    @NotBlank(message = "policyId is required")
    private String policyId;

    private Map<String, Object> startFormData;
}
