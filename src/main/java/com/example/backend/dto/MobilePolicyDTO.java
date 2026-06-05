package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Portal móvil — una política disponible para iniciar trámite desde la
 * app (Módulo 3). Incluye el formulario inicial para que la app lo
 * renderice dinámicamente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobilePolicyDTO {

    private String id;
    private String name;
    private String description;
    private FormDefinitionDTO startFormDefinition;
}
