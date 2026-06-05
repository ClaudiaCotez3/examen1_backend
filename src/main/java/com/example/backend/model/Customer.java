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

/**
 * First-class customer identity (Opción B of the Repositorio Documental).
 *
 * Until now the "cliente" only existed inside each trámite's
 * {@code startFormData} (reserved fields cliente_nombre / cliente_email /
 * cliente_ci). That heuristic-at-read-time approach doesn't scale to the
 * roadmap: NL reports (Módulo 4) and predictive analytics (Módulo 5) need a
 * stable key to join trámites per client.
 *
 * Identity resolution happens ONCE, at write time, in
 * CustomerResolutionService (find-or-create by email, then CI). Every new
 * {@link Procedure} carries {@code cliente_id}; legacy cases are linked by
 * the idempotent startup backfill (CustomerBackfillRunner).
 *
 * Deliberately minimal — this is an identity record, not a CRM profile.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "clientes")
public class Customer {

    @Id
    private ObjectId id;

    private String nombre;

    /** Primary identity key (matched case-insensitively). Not unique at the
     *  DB level so the backfill never crashes on legacy dirty data; the
     *  resolution service always reuses the first match. */
    @Indexed
    private String email;

    /** Secondary identity key (cédula). */
    @Indexed
    private String ci;

    @Field("fecha_creacion")
    private LocalDateTime fechaCreacion;

    @Field("fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
