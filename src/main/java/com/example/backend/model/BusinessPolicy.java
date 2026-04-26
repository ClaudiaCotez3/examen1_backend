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
import java.util.Map;

/**
 * Definition-layer root: one business process (e.g. "Instalación de router",
 * "Apertura de cuenta"). Each policy owns a BPMN diagram, a set of lanes
 * ({@link Lane} / Department), activities, and flows.
 *
 * This is the *definition* layer only — runtime state lives in
 * {@link Procedure} / {@link ActivityInstance}.
 */
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
     * Monotonically increasing version number. Mirrors the latest
     * {@link PolicyVersion}.numeroVersion so reads don't have to join.
     * Starts at 1 when a new policy is first persisted.
     */
    private Integer version;

    /**
     * Authoritative BPMN 2.0 XML for the diagram. Stored so the visual
     * designer can re-open the policy without losing positions or geometry,
     * and so the parser can re-derive the structured graph if the
     * normalized lanes/activities/flows ever drift out of sync.
     */
    @Field("bpmn_xml")
    private String bpmnXml;

    /**
     * Dynamic form the consultor fills when initiating a case for this
     * process. Replaces the old free-text {@code requisitos_previos} list
     * — instead of bullet points, the customer now provides structured data
     * that is captured on the {@link Procedure} at start time.
     */
    @Field("start_form_definition")
    private FormDefinition startFormDefinition;

    /**
     * Opaque form-js editor schema kept alongside {@link #startFormDefinition}
     * so the admin re-opens the start form in the builder with the same
     * layout, labels and component order they authored.
     */
    @Field("start_form_schema")
    private Map<String, Object> startFormSchema;

    @Field("fecha_creacion")
    private LocalDateTime fechaCreacion;

    @Field("fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
