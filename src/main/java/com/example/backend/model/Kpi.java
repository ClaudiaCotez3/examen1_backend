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
@Document(collection = "kpis")
public class Kpi {

    @Id
    private ObjectId id;

    @Field("politica_id")
    private ObjectId politicaId;

    @Field("actividad_id")
    private ObjectId actividadId;

    @Field("tiempo_promedio")
    private Double tiempoPromedio;

    private Integer cantidad;

    private Double eficiencia;
}
