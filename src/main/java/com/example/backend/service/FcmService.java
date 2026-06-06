package com.example.backend.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Envío de push notifications vía Firebase Cloud Messaging (HTTP v1).
 *
 * Inicializa el Firebase Admin SDK con la clave de cuenta de servicio
 * ({@code app.fcm.service-account-file}). Si el archivo no existe el
 * servicio queda DESHABILITADO con un aviso claro — el resto del sistema
 * (incluida la creación de notificaciones en Mongo) sigue funcionando:
 * el push es un canal de entrega, no la fuente de verdad.
 */
@Slf4j
@Service
public class FcmService {

    private final String serviceAccountPath;
    private boolean enabled = false;

    public FcmService(
            @Value("${app.fcm.service-account-file:./config/firebase-service-account.json}")
            String serviceAccountPath) {
        this.serviceAccountPath = serviceAccountPath;
    }

    @PostConstruct
    void init() {
        Path path = Paths.get(serviceAccountPath);
        if (!Files.exists(path)) {
            log.warn("FCM deshabilitado: no existe la clave de cuenta de servicio en {} "
                    + "(coloca el JSON de Firebase Console → Cuentas de servicio "
                    + "→ Generar clave privada).", path.toAbsolutePath());
            return;
        }
        try (FileInputStream credentials = new FileInputStream(path.toFile())) {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(credentials))
                        .build());
            }
            enabled = true;
            log.info("FCM habilitado (cuenta de servicio: {})", path.toAbsolutePath());
        } catch (Exception e) {
            log.error("FCM deshabilitado: la clave de cuenta de servicio no es válida", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Envía una push a un token. Devuelve false cuando el token ya no es
     * válido (app desinstalada / token rotado) para que el llamador lo
     * elimine del registro de dispositivos.
     */
    public boolean send(String fcmToken, String title, String body, Map<String, String> data) {
        if (!enabled) return true; // canal apagado — no es un token inválido
        try {
            Message message = Message.builder()
                    .setToken(fcmToken)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putAllData(data == null ? Map.of() : data)
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(AndroidNotification.builder()
                                    .setChannelId("tramites")
                                    .build())
                            .build())
                    .build();
            FirebaseMessaging.getInstance().send(message);
            return true;
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED
                    || code == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("Token FCM inválido/expirado — se eliminará: {}", code);
                return false;
            }
            log.warn("Fallo al enviar push FCM ({}): {}", code, e.getMessage());
            return true;
        } catch (Exception e) {
            log.warn("Fallo inesperado al enviar push FCM", e);
            return true;
        }
    }
}
