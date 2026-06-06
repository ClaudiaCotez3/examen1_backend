package com.example.backend.service;

import com.example.backend.model.CustomerDevice;
import com.example.backend.model.CustomerNotification;
import com.example.backend.model.Lane;
import com.example.backend.model.Procedure;
import com.example.backend.repository.CustomerDeviceRepository;
import com.example.backend.repository.CustomerNotificationRepository;
import com.example.backend.repository.LaneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Notificaciones al CLIENTE en los hitos de su trámite (portal móvil).
 *
 * Tres eventos, enganchados en el motor de workflow:
 *   - CASE_STARTED   → el trámite quedó registrado (startCase).
 *   - AREA_CHANGED   → el trámite avanzó a otra área/departamento
 *                      (una tarea se desbloqueó en una lane distinta).
 *   - CASE_FINISHED  → todas las actividades terminaron.
 *
 * Doble canal: la notificación SIEMPRE se persiste en Mongo (campanita e
 * historial de la app) y, si FCM está habilitado, además se envía push a
 * cada dispositivo registrado del cliente. Cualquier fallo aquí se traga
 * con log — las notificaciones jamás rompen el motor de workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerNotificationService {

    public static final String TYPE_CASE_STARTED = "CASE_STARTED";
    public static final String TYPE_AREA_CHANGED = "AREA_CHANGED";
    public static final String TYPE_CASE_FINISHED = "CASE_FINISHED";

    private final CustomerNotificationRepository notificationRepository;
    private final CustomerDeviceRepository deviceRepository;
    private final LaneRepository laneRepository;
    private final FcmService fcmService;

    // ── Eventos del trámite ───────────────────────────────────────────────

    public void notifyCaseStarted(Procedure caseFile, String policyName) {
        String proceso = policyName != null && !policyName.isBlank()
                ? policyName : "tu trámite";
        dispatch(caseFile, TYPE_CASE_STARTED,
                "¡Tu trámite fue registrado!",
                "El trámite " + caseFile.getCodigo() + " (" + proceso
                        + ") quedó registrado y ya está en proceso. "
                        + "Te avisaremos de cada avance.");
    }

    public void notifyAreaChanged(Procedure caseFile, ObjectId newLaneId) {
        String area = laneRepository.findById(newLaneId)
                .map(Lane::getNombre)
                .orElse("una nueva área");
        dispatch(caseFile, TYPE_AREA_CHANGED,
                "Tu trámite avanzó de área",
                "El trámite " + caseFile.getCodigo()
                        + " ahora está en el área de " + area + ".");
    }

    public void notifyCaseFinished(Procedure caseFile) {
        dispatch(caseFile, TYPE_CASE_FINISHED,
                "¡Tu trámite finalizó!",
                "El trámite " + caseFile.getCodigo()
                        + " se completó. Gracias por tu paciencia.");
    }

    // ── Núcleo: persistir + push ──────────────────────────────────────────

    private void dispatch(Procedure caseFile, String tipo, String titulo, String mensaje) {
        try {
            ObjectId clienteId = caseFile.getClienteId();
            if (clienteId == null) {
                // Trámite legado sin cliente identificado — nada que notificar.
                return;
            }
            CustomerNotification notification = notificationRepository.save(
                    CustomerNotification.builder()
                            .clienteId(clienteId)
                            .tramiteId(caseFile.getId())
                            .caseCode(caseFile.getCodigo())
                            .tipo(tipo)
                            .titulo(titulo)
                            .mensaje(mensaje)
                            .fecha(LocalDateTime.now())
                            .leida(false)
                            .build());

            List<CustomerDevice> devices = deviceRepository.findByClienteId(clienteId);
            Map<String, String> data = Map.of(
                    "type", tipo,
                    "caseId", caseFile.getId().toHexString(),
                    "caseCode", caseFile.getCodigo() != null ? caseFile.getCodigo() : ""
            );
            for (CustomerDevice device : devices) {
                boolean stillValid = fcmService.send(
                        device.getFcmToken(), titulo, mensaje, data);
                if (!stillValid) {
                    deviceRepository.deleteByFcmToken(device.getFcmToken());
                }
            }
            log.info("Notificación {} para cliente {} (caso {}) — {} dispositivo(s)",
                    tipo, clienteId, caseFile.getCodigo(), devices.size());
            // La fila persiste aunque no haya dispositivos: la app la mostrará
            // en la campanita al refrescar.
            if (notification.getId() == null) {
                log.warn("Notificación sin id tras guardar (inesperado)");
            }
        } catch (Exception e) {
            // Las notificaciones nunca rompen el flujo del trámite.
            log.warn("No se pudo despachar la notificación {} del caso {}",
                    tipo, caseFile.getId(), e);
        }
    }
}
