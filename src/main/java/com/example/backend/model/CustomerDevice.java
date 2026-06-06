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
 * Dispositivo móvil de un cliente registrado para push (colección
 * `dispositivos_cliente`). Un cliente puede tener varios (teléfono +
 * tablet); el token FCM identifica la instalación de la app, así que el
 * registro hace upsert por token — si otro cliente inicia sesión en el
 * mismo teléfono, el token se re-asigna y deja de notificar al anterior.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "dispositivos_cliente")
public class CustomerDevice {

    @Id
    private ObjectId id;

    @Indexed
    @Field("cliente_id")
    private ObjectId clienteId;

    @Indexed(unique = true)
    @Field("fcm_token")
    private String fcmToken;

    private String plataforma;

    @Field("fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
