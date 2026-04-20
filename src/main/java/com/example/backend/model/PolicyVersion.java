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

    /**
     * BPMN XML captured at the moment this version was minted. Lets the
     * audit log re-render any historical version of the diagram even after
     * the live `BusinessPolicy.bpmnXml` has moved on.
     *
     * Nullable for back-compat with versions created before BPMN support.
     */
    @Field("bpmn_xml_snapshot")
    private String bpmnXmlSnapshot;

    @Field("fecha_publicacion")
    private LocalDateTime fechaPublicacion;
}
