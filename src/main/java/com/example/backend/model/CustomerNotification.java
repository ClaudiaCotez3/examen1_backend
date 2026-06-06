package com.example.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * Notificación dirigida a un CLIENTE del portal móvil (colección
 * `notificaciones`).
 *
 * Se genera en los hitos del trámite que le importan al cliente:
 *   - CASE_STARTED   → su trámite quedó registrado y en marcha.
 *   - AREA_CHANGED   → el trámite avanzó a otra área/departamento.
 *   - CASE_FINISHED  → el trámite terminó.
 *
 * La fila en Mongo es la fuente de verdad (historial consultable desde la
 * app con la campanita); el push FCM es el aviso en el teléfono — si el
 * envío push falla, la notificación igual queda registrada.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notificaciones")
@CompoundIndex(name = "cliente_fecha_idx", def = "{ 'cliente_id': 1, 'fecha': -1 }")
public class CustomerNotification {

    @Id
    private ObjectId id;

    @Field("cliente_id")
    private ObjectId clienteId;

    @Field("tramite_id")
    private ObjectId tramiteId;

    @Field("codigo_tramite")
    private String caseCode;

    /** CASE_STARTED | AREA_CHANGED | CASE_FINISHED */
    private String tipo;

    private String titulo;

    private String mensaje;

    private LocalDateTime fecha;

    private boolean leida;
}
