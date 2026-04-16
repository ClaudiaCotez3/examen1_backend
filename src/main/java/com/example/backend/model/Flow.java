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
@Document(collection = "flujos")
public class Flow {

    @Id
    private ObjectId id;

    @Field("actividad_origen_id")
    private ObjectId actividadOrigenId;

    @Field("actividad_destino_id")
    private ObjectId actividadDestinoId;

    /** LINEAR | CONDITIONAL | PARALLEL | LOOP */
    private String tipo;

    private String condicion;
}
