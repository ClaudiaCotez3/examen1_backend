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
import java.util.List;

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
     * Process-level prerequisites (e.g. "Documento de identidad", "Factura
     * de luz"). Validated *before* a {@link Procedure} can be started for
     * this policy — not per activity. Plain strings to stay open for the UI.
     */
    @Field("requisitos_previos")
    private List<String> prerequisitos;

    @Field("fecha_creacion")
    private LocalDateTime fechaCreacion;

    @Field("fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
