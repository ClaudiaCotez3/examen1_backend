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
@Document(collection = "instancias_actividad")
public class ActivityInstance {

    @Id
    private ObjectId id;

    @Field("tramite_id")
    private ObjectId tramiteId;

    @Field("actividad_id")
    private ObjectId actividadId;

    /** en_espera | en_proceso | finalizado */
    private String estado;

    @Field("asignado_a")
    private ObjectId asignadoA;

    @Field("fecha_inicio")
    private LocalDateTime fechaInicio;

    @Field("fecha_fin")
    private LocalDateTime fechaFin;
}
