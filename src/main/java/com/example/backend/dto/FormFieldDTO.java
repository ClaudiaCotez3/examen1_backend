package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Field descriptor exchanged with the frontend (mirrors BPMN extension). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormFieldDTO {

    @NotBlank(message = "Field name is required")
    private String name;

    private String label;

    /** text | number | date | boolean | select | file */
    @NotBlank(message = "Field type is required")
    private String type;

    private Boolean required;

    private List<String> options;
}
