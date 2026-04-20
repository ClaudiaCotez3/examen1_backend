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
@Document(collection = "politicas_negocio")
public class BusinessPolicy {

    @Id
    private ObjectId id;

    private String nombre;

    private String descripcion;

    /** DRAFT | ACTIVE | ARCHIVED (ARCHIVED is used for logical delete) */
    private String estado;

    /**
     * Authoritative BPMN 2.0 XML for the diagram. Stored so the visual
     * designer can re-open the policy without losing positions or geometry,
     * and so the parser can re-derive the structured graph if the
     * normalized lanes/activities/flows ever drift out of sync.
     *
     * Nullable: pre-existing policies created before BPMN support and
     * polices created via the plain `POST /api/policies` endpoint won't
     * have an XML payload attached.
     */
    @Field("bpmn_xml")
    private String bpmnXml;

    @Field("fecha_creacion")
    private LocalDateTime fechaCreacion;

    @Field("fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
