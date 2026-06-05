package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "tramites")
public class Procedure {

    @Id
    private ObjectId id;

    @Indexed(unique = true)
    private String codigo;

    @Field("version_politica_id")
    private ObjectId versionPoliticaId;

    /** activo | finalizado */
    private String estado;

    /**
     * Structured payload captured from the start form when the case was
     * opened. Shape is driven by {@link BusinessPolicy#getStartFormDefinition()}:
     * scalar fields map to primitives, groups to nested maps, dynamic lists
     * to arrays of maps. Stored as a loose map so the schema can evolve
     * without a model migration.
     */
    @Field("start_form_data")
    private Map<String, Object> startFormData;

    /**
     * First-class link to the {@link Customer} this trámite belongs to.
     * Resolved ONCE at case creation (find-or-create by the reserved
     * cliente_email / cliente_ci start-form fields); legacy cases are
     * linked retroactively by the startup backfill. Null when the case
     * predates start-forms and carries no identifiable customer data.
     */
    @Indexed
    @Field("cliente_id")
    private ObjectId clienteId;

    @Field("fecha_inicio")
    private LocalDateTime fechaInicio;

    @Field("fecha_fin")
    private LocalDateTime fechaFin;
}
