package com.example.backend.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestión Documental — salas de co-edición de documentos en memoria.
 *
 * Mismo modelo de presencia que la colaboración del diseñador BPMN
 * (PolicyCollaborationService) pero con espacio de claves propio
 * (documentId) y tópicos /topic/documents/** — así un drop de sesión de
 * una sala de documentos nunca contamina la presencia del diseñador.
 */
@Service
public class DocumentCollaborationService {

    /** documentId → (sessionId → email). LinkedHashMap preserva orden de llegada. */
    private final Map<String, LinkedHashMap<String, String>> rooms = new ConcurrentHashMap<>();

    public synchronized List<String> join(String documentId, String email, String sessionId) {
        rooms.computeIfAbsent(documentId, k -> new LinkedHashMap<>())
                .put(sessionId, email);
        return members(documentId);
    }

    public synchronized List<String> leave(String documentId, String email, String sessionId) {
        LinkedHashMap<String, String> room = rooms.get(documentId);
        if (room != null) {
            room.remove(sessionId);
            if (room.isEmpty()) rooms.remove(documentId);
        }
        return members(documentId);
    }

    /** @return resultado del drop, o null si la sesión no estaba en ninguna sala. */
    public synchronized DisconnectResult dropSession(String sessionId) {
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : rooms.entrySet()) {
            if (entry.getValue().remove(sessionId) != null) {
                String documentId = entry.getKey();
                if (entry.getValue().isEmpty()) rooms.remove(documentId);
                return new DisconnectResult(documentId, members(documentId));
            }
        }
        return null;
    }

    private List<String> members(String documentId) {
        LinkedHashMap<String, String> room = rooms.get(documentId);
        if (room == null) return List.of();
        // Dedup conservando orden (el mismo usuario puede tener 2 pestañas).
        Set<String> unique = new LinkedHashSet<>(room.values());
        return new ArrayList<>(unique);
    }

    public record DisconnectResult(String documentId, List<String> members) { }
}
