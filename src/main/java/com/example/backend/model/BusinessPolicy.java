package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "politicas_negocio")
public class BusinessPolicy {

    @Id
    private ObjectId id;

    private String nombre;

    private String descripcion;

    /** DRAFT | ACTIVE | ARCHIVED (ARCHIVED is used for logical delete) */
    private String estado;

    @Field("fecha_creacion")
    private LocalDateTime fechaCreacion;

    @Field("fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
