package com.example.backend.controller;

import com.example.backend.dto.CollabCursorDTO;
import com.example.backend.dto.CollabDiagramEventDTO;
import com.example.backend.dto.CollabPresenceDTO;
import com.example.backend.dto.CollabStartFormDTO;
import com.example.backend.security.StompJwtChannelInterceptor.CollabPrincipal;
import com.example.backend.service.PolicyCollaborationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;

/**
 * STOMP entry points for the collaborative designer.
 *
 * Lifecycle:
 *   1. The Angular client sends CONNECT with the JWT — the channel
 *      interceptor accepts only ADMIN users.
 *   2. After the modeler is ready it sends /app/policies/{id}/join with
 *      no payload; the server adds the user to the in-memory room and
 *      broadcasts the new presence list.
 *   3. Each local change in the modeler is serialized to BPMN XML and
 *      pushed to /app/policies/{id}/diagram. The server fans it back out
 *      to /topic/policies/{id}/diagram so the other admins import it.
 *   4. Mouse moves go to /app/policies/{id}/cursor — same fan-out.
 *   5. /app/policies/{id}/leave (or a clean disconnect) removes the
 *      session and re-broadcasts presence.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PolicyCollaborationController {

    private final PolicyCollaborationService collaborationService;
    private final SimpMessagingTemplate messaging;

    // ── Diagram XML fan-out ────────────────────────────────────────────────

    @MessageMapping("/policies/{policyId}/diagram")
    public void onDiagram(
            @DestinationVariable String policyId,
            @Payload CollabDiagramEventDTO event,
            Principal principal) {
        // Stamp the sender so the receiver can ignore its own echo.
        if (principal instanceof CollabPrincipal cp) {
            event.setSenderEmail(cp.getEmail());
        }
        messaging.convertAndSend("/topic/policies/" + policyId + "/diagram", event);
    }

    // ── Start-form fan-out ─────────────────────────────────────────────────

    /**
     * Re-broadcasts the policy's start form (initial customer-facing
     * dynamic form). The diagram XML doesn't carry this — it lives in
     * {@code BusinessPolicy.startFormDefinition} — so we ship the
     * definition + form-js schema on a dedicated topic so every admin
     * in the room mirrors whatever the editing admin just configured.
     */
    @MessageMapping("/policies/{policyId}/start-form")
    public void onStartForm(
            @DestinationVariable String policyId,
            @Payload CollabStartFormDTO event,
            Principal principal) {
        if (principal instanceof CollabPrincipal cp) {
            event.setSenderEmail(cp.getEmail());
        }
        int fieldCount = event.getDefinition() == null
                ? 0
                : (event.getDefinition().get("fields") instanceof java.util.List<?> list ? list.size() : 0);
        log.info("[Collab] start-form fan-out policyId={} sender={} fields={}",
                policyId, event.getSenderEmail(), fieldCount);
        messaging.convertAndSend("/topic/policies/" + policyId + "/start-form", event);
    }

    // ── Cursor fan-out ─────────────────────────────────────────────────────

    @MessageMapping("/policies/{policyId}/cursor")
    public void onCursor(
            @DestinationVariable String policyId,
            @Payload CollabCursorDTO cursor,
            Principal principal) {
        if (principal instanceof CollabPrincipal cp) {
            cursor.setSenderEmail(cp.getEmail());
        }
        messaging.convertAndSend("/topic/policies/" + policyId + "/cursor", cursor);
    }

    // ── Presence ───────────────────────────────────────────────────────────

    /**
     * Adds the current admin to the room and broadcasts the new presence
     * list. The joiner is subscribed to the same topic, so the broadcast
     * doubles as the "you joined" reply — no need for a private queue.
     */
    @MessageMapping("/policies/{policyId}/join")
    public void onJoin(
            @DestinationVariable String policyId,
            StompHeaderAccessor accessor,
            Principal principal) {
        if (!(principal instanceof CollabPrincipal cp)) return;
        List<String> members = collaborationService.join(
                policyId, cp.getEmail(), accessor.getSessionId());
        messaging.convertAndSend("/topic/policies/" + policyId + "/presence",
                CollabPresenceDTO.builder().policyId(policyId).emails(members).build());
    }

    @MessageMapping("/policies/{policyId}/leave")
    public void onLeave(
            @DestinationVariable String policyId,
            StompHeaderAccessor accessor,
            Principal principal) {
        if (!(principal instanceof CollabPrincipal cp)) return;
        List<String> members = collaborationService.leave(policyId, cp.getEmail(), accessor.getSessionId());
        messaging.convertAndSend("/topic/policies/" + policyId + "/presence",
                CollabPresenceDTO.builder().policyId(policyId).emails(members).build());
    }

    /**
     * Cleans up presence when the WebSocket drops without an explicit
     * leave — common when the admin closes the tab.
     */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        PolicyCollaborationService.DisconnectResult result =
                collaborationService.dropSession(event.getSessionId());
        if (result == null) return;
        messaging.convertAndSend(
                "/topic/policies/" + result.policyId() + "/presence",
                CollabPresenceDTO.builder()
                        .policyId(result.policyId())
                        .emails(result.members())
                        .build());
    }
}
