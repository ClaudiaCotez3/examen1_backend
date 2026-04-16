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
@Document(collection = "versiones_politica")
public class PolicyVersion {

    @Id
    private ObjectId id;

    @Field("politica_id")
    private ObjectId politicaId;

    @Field("numero_version")
    private Integer numeroVersion;

    /** ACTIVE | INACTIVE */
    private String estado;

    @Field("fecha_publicacion")
    private LocalDateTime fechaPublicacion;
}
