package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "tramites")
public class Procedure {

    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String codigo;

    @Field("version_politica_id")
    private ObjectId versionPoliticaId;

    /** activo | finalizado */
    private String estado;

    @Field("fecha_inicio")
    private LocalDateTime fechaInicio;
}
