package com.example.backend.controller;

import com.example.backend.dto.CollabDocContentDTO;
import com.example.backend.dto.CollabPresenceDTO;
import com.example.backend.security.StompJwtChannelInterceptor.CollabPrincipal;
import com.example.backend.service.DocumentCollaborationService;
import lombok.RequiredArgsConstructor;
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
 * Gestión Documental — STOMP para la co-edición de documentos del
 * expediente (mismo endpoint /ws/policies que el diseñador, salas propias).
 *
 *   /app/documents/{docId}/join     → entrar a la sala (presencia)
 *   /app/documents/{docId}/leave    → salir
 *   /app/documents/{docId}/content  → difundir el estado del editor
 *
 *   /topic/documents/{docId}/presence — quiénes están editando
 *   /topic/documents/{docId}/content  — contenido en vivo
 */
@Controller
@RequiredArgsConstructor
public class DocumentCollaborationController {

    private final DocumentCollaborationService collaborationService;
    private final SimpMessagingTemplate messaging;

    @MessageMapping("/documents/{documentId}/content")
    public void onContent(
            @DestinationVariable String documentId,
            @Payload CollabDocContentDTO event,
            Principal principal) {
        if (principal instanceof CollabPrincipal cp) {
            event.setSenderEmail(cp.getEmail());
        }
        messaging.convertAndSend("/topic/documents/" + documentId + "/content", event);
    }

    @MessageMapping("/documents/{documentId}/join")
    public void onJoin(
            @DestinationVariable String documentId,
            StompHeaderAccessor accessor,
            Principal principal) {
        if (!(principal instanceof CollabPrincipal cp)) return;
        List<String> members = collaborationService.join(
                documentId, cp.getEmail(), accessor.getSessionId());
        broadcastPresence(documentId, members);
    }

    @MessageMapping("/documents/{documentId}/leave")
    public void onLeave(
            @DestinationVariable String documentId,
            StompHeaderAccessor accessor,
            Principal principal) {
        if (!(principal instanceof CollabPrincipal cp)) return;
        List<String> members = collaborationService.leave(
                documentId, cp.getEmail(), accessor.getSessionId());
        broadcastPresence(documentId, members);
    }

    /** Limpia la presencia cuando el socket cae sin leave explícito. */
    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        DocumentCollaborationService.DisconnectResult result =
                collaborationService.dropSession(event.getSessionId());
        if (result == null) return;
        broadcastPresence(result.documentId(), result.members());
    }

    private void broadcastPresence(String documentId, List<String> members) {
        messaging.convertAndSend(
                "/topic/documents/" + documentId + "/presence",
                CollabPresenceDTO.builder().policyId(documentId).emails(members).build());
    }
}
