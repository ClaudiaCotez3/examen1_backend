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
@Document(collection = "campos_formulario")
public class FormField {

    @Id
    private ObjectId id;

    @Field("formulario_id")
    private ObjectId formularioId;

    private String nombre;

    /** texto | numero | fecha | select | archivo */
    private String tipo;

    private Boolean requerido;
}
