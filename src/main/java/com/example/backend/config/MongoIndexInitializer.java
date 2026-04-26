package com.example.backend.config;

import com.example.backend.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Crea todos los índices del modelo al arranque de la aplicación.
 * Complementa los índices declarados con @Indexed en las entidades.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoIndexInitializer {

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void initIndexes() {
        log.info("Inicializando índices MongoDB...");

        // usuarios
        IndexOperations users = mongoTemplate.indexOps(User.class);
        users.createIndex(new Index().on("rol_id", Sort.Direction.ASC).on("activo", Sort.Direction.ASC));

        // politicas_negocio
        IndexOperations policies = mongoTemplate.indexOps(BusinessPolicy.class);
        policies.createIndex(new Index().on("estado", Sort.Direction.ASC).on("fecha_creacion", Sort.Direction.DESC));

        // versiones_politica
        IndexOperations versions = mongoTemplate.indexOps(PolicyVersion.class);
        versions.createIndex(new Index().on("politica_id", Sort.Direction.ASC).on("numero_version", Sort.Direction.DESC));
        versions.createIndex(new Index().on("politica_id", Sort.Direction.ASC).on("estado", Sort.Direction.ASC));
        dropIndexIfExists(versions, "uniq_version_activa_por_politica");
        versions.createIndex(new Index()
                .on("politica_id", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(Criteria.where("estado").is("ACTIVE")))
                .named("uniq_version_activa_por_politica"));

        // calles
        IndexOperations lanes = mongoTemplate.indexOps(Lane.class);
        lanes.createIndex(new Index().on("politica_id", Sort.Direction.ASC).on("posicion", Sort.Direction.ASC));

        // actividades
        IndexOperations activities = mongoTemplate.indexOps(Activity.class);
        activities.createIndex(new Index().on("politica_id", Sort.Direction.ASC).on("calle_id", Sort.Direction.ASC));
        activities.createIndex(new Index().on("politica_id", Sort.Direction.ASC).on("tipo", Sort.Direction.ASC));

        // flujos
        IndexOperations flows = mongoTemplate.indexOps(Flow.class);
        flows.createIndex(new Index().on("actividad_origen_id", Sort.Direction.ASC));
        flows.createIndex(new Index().on("actividad_destino_id", Sort.Direction.ASC));

        // campos_formulario
        IndexOperations formFields = mongoTemplate.indexOps(FormField.class);
        formFields.createIndex(new Index().on("formulario_id", Sort.Direction.ASC));

        // tramites
        IndexOperations procedures = mongoTemplate.indexOps(Procedure.class);
        procedures.createIndex(new Index().on("estado", Sort.Direction.ASC).on("fecha_inicio", Sort.Direction.DESC));
        procedures.createIndex(new Index().on("version_politica_id", Sort.Direction.ASC).on("estado", Sort.Direction.ASC));

        // instancias_actividad (críticos)
        IndexOperations activityInstances = mongoTemplate.indexOps(ActivityInstance.class);
        activityInstances.createIndex(new Index()
                .on("asignado_a", Sort.Direction.ASC)
                .on("estado", Sort.Direction.ASC)
                .on("fecha_inicio", Sort.Direction.DESC));
        activityInstances.createIndex(new Index().on("tramite_id", Sort.Direction.ASC).on("estado", Sort.Direction.ASC));
        activityInstances.createIndex(new Index().on("actividad_id", Sort.Direction.ASC).on("estado", Sort.Direction.ASC));
        // Phase 4 — operator Kanban global scan (no filters): sort by fecha_inicio DESC
        activityInstances.createIndex(new Index()
                .on("estado", Sort.Direction.ASC)
                .on("fecha_inicio", Sort.Direction.DESC));
        // Pool visibility: WAITING tasks where the operator is in the eligible
        // pool. Mongo can use a multikey compound index to serve this query
        // without a collection scan as soon as the data set grows.
        activityInstances.createIndex(new Index()
                .on("usuarios_asignados", Sort.Direction.ASC)
                .on("estado", Sort.Direction.ASC));

        // respuestas_formulario (Phase 5 — single doc per submission, JSON data map)
        IndexOperations formResponses = mongoTemplate.indexOps(FormResponse.class);
        // Drop legacy per-field index if it survives from the old "one doc per field" layout.
        dropIndexIfExists(formResponses, "campo_id_1_valor_1");
        // Drop the auto-created index from the previous @Indexed annotation
        // (named after the field) so we can claim its key with our own named index.
        dropIndexIfExists(formResponses, "instancia_actividad_id");
        formResponses.createIndex(new Index()
                .on("instancia_actividad_id", Sort.Direction.ASC)
                .unique()
                .named("uniq_form_response_por_instancia"));
        formResponses.createIndex(new Index()
                .on("submitted_by", Sort.Direction.ASC)
                .on("submitted_at", Sort.Direction.DESC));

        // historial_tramite
        IndexOperations procedureHistory = mongoTemplate.indexOps(ProcedureHistory.class);
        procedureHistory.createIndex(new Index().on("tramite_id", Sort.Direction.ASC).on("fecha", Sort.Direction.DESC));
        procedureHistory.createIndex(new Index().on("usuario_id", Sort.Direction.ASC).on("fecha", Sort.Direction.DESC));

        // kpis
        IndexOperations kpis = mongoTemplate.indexOps(Kpi.class);
        kpis.createIndex(new Index()
                .on("politica_id", Sort.Direction.ASC)
                .on("actividad_id", Sort.Direction.ASC)
                .unique()
                .named("uniq_kpi_por_politica_actividad"));

        // registros_ia
        IndexOperations aiLogs = mongoTemplate.indexOps(AiLog.class);
        aiLogs.createIndex(new Index().on("tramite_id", Sort.Direction.ASC).on("fecha", Sort.Direction.DESC));
        aiLogs.createIndex(new Index().on("tipo", Sort.Direction.ASC).on("fecha", Sort.Direction.DESC));

        log.info("Índices MongoDB inicializados correctamente.");
    }

    /**
     * Drops an index by name if it exists. Used to re-create indexes whose definition
     * changed (e.g. partial filter expression updated), since MongoDB refuses to mutate
     * a named index in place.
     */
    private void dropIndexIfExists(IndexOperations ops, String indexName) {
        boolean exists = ops.getIndexInfo().stream()
                .anyMatch(info -> indexName.equals(info.getName()));
        if (exists) {
            log.info("Recreando índice {} (definición actualizada)", indexName);
            ops.dropIndex(indexName);
        }
    }
}
