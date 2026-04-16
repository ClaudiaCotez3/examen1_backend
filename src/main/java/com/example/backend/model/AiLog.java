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
@Document(collection = "registros_ia")
public class AiLog {

    @Id
    private ObjectId id;

    @Field("tramite_id")
    private ObjectId tramiteId;

    /** clasificacion | transcripcion | analisis */
    private String tipo;

    private String input;

    private String output;

    private LocalDateTime fecha;
}
