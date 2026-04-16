package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "respuestas_formulario")
public class FormResponse {

    @Id
    private ObjectId id;

    @Field("instancia_actividad_id")
    private ObjectId instanciaActividadId;

    @Field("campo_id")
    private ObjectId campoId;

    private String valor;
}
