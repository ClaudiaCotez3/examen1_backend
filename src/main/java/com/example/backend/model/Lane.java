package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * BPMN Lane = business <b>Department</b>.
 *
 * In the definition layer a lane is a department (e.g. "Instalación",
 * "Atención al cliente") that owns a set of activities. The BPMN roots call
 * it a Lane because that is the source artifact, but in the business domain
 * we expose it as Department throughout the API (see
 * {@code DepartmentRequestDTO} / {@code DepartmentResponseDTO}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "calles")
public class Lane {

    @Id
    private ObjectId id;

    @Field("politica_id")
    private ObjectId politicaId;

    private String nombre;

    private Integer posicion;
}
