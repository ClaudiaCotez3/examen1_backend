package com.example.backend.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Full form schema as understood by the frontend / BPMN modeller. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormDefinitionDTO {

    @Valid
    private List<FormFieldDTO> fields;
}
