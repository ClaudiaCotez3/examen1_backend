package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "actividades")
public class Activity {

    @Id
    private ObjectId id;

    @Field("politica_id")
    private ObjectId politicaId;

    @Field("calle_id")
    private ObjectId calleId;

    private String nombre;

    /** START | TASK | DECISION | END */
    private String tipo;

    @Field("requiere_formulario")
    private Boolean requiereFormulario;

    /**
     * Embedded JSON-shaped form schema.
     *
     * Stored as a sub-document (not a separate collection) because:
     *   1) It is always read together with the activity — there is no use case
     *      for fetching the form without its activity.
     *   2) The shape is intentionally open: BPMN extensions can introduce new
     *      field types without an entity migration. A polymorphic JSON body
     *      removes the need for a fixed relational schema and avoids alter-table
     *      style changes whenever the modeller adds a new control.
     *   3) MongoDB documents handle this natively, so we get strong typing for
     *      the well-known properties (name, type, required) while still allowing
     *      the modeller to attach arbitrary metadata via {@link FormFieldDefinition}.
     */
    @Field("definicion_formulario")
    private FormDefinition formDefinition;
}
