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
@Document(collection = "actividades")
public class Activity {

    @Id
    private ObjectId id;

    @Field("politica_id")
    private ObjectId politicaId;

    @Field("calle_id")
    private ObjectId calleId;

    private String nombre;

    /** START | TASK | DECISION | END */
    private String tipo;

    @Field("requiere_formulario")
    private Boolean requiereFormulario;
}
