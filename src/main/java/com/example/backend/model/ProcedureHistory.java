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
@Document(collection = "historial_tramite")
public class ProcedureHistory {

    @Id
    private ObjectId id;

    @Field("tramite_id")
    private ObjectId tramiteId;

    @Field("actividad_id")
    private ObjectId actividadId;

    @Field("usuario_id")
    private ObjectId usuarioId;

    private String accion;

    private LocalDateTime fecha;
}
