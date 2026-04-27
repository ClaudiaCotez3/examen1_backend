package com.example.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory presence registry for the collaborative designer.
 *
 * Each policy id is a "room"; admins joining the room get added to the
 * shared list, leaving removes them, and a single STOMP session can only
 * be in one room at a time (the front-end opens one designer at a time).
 *
 * Plain ConcurrentHashMap is enough — collaboration is ephemeral; if the
 * server restarts everyone simply re-joins. No persistence needed.
 */
@Slf4j
@Service
public class PolicyCollaborationService {

    /** policyId → ordered list of emails currently in that room. */
    private final Map<String, List<String>> roomMembers = new ConcurrentHashMap<>();

    /** stompSessionId → { policyId, email } so we can clean up on disconnect. */
    private final Map<String, SessionBinding> sessionBindings = new ConcurrentHashMap<>();

    public synchronized List<String> join(String policyId, String email, String sessionId) {
        List<String> members = roomMembers.computeIfAbsent(policyId,
                id -> new CopyOnWriteArrayList<>());
        if (!members.contains(email)) {
            members.add(email);
        }
        sessionBindings.put(sessionId, new SessionBinding(policyId, email));
        log.info("Collab join: policyId={}, email={}, sessionId={}, members={}",
                policyId, email, sessionId, members);
        return new ArrayList<>(members);
    }

    public synchronized List<String> leave(String policyId, String email, String sessionId) {
        sessionBindings.remove(sessionId);
        List<String> members = roomMembers.get(policyId);
        if (members == null) {
            return Collections.emptyList();
        }
        // Only remove this email from the room when no other session of the
        // same admin is still connected. An admin with two tabs open should
        // keep showing once.
        boolean stillElsewhere = sessionBindings.values().stream()
                .anyMatch(b -> policyId.equals(b.policyId) && email.equals(b.email));
        if (!stillElsewhere) {
            members.remove(email);
        }
        if (members.isEmpty()) {
            roomMembers.remove(policyId);
        }
        log.info("Collab leave: policyId={}, email={}, sessionId={}, members={}",
                policyId, email, sessionId, members);
        return new ArrayList<>(members);
    }

    /**
     * Drops a session without knowing its room (the WebSocket disconnect
     * event carries only the sessionId). Returns the room that was left
     * and the new member list, or null if the session wasn't tracked.
     */
    public synchronized DisconnectResult dropSession(String sessionId) {
        SessionBinding binding = sessionBindings.remove(sessionId);
        if (binding == null) {
            return null;
        }
        List<String> members = roomMembers.get(binding.policyId);
        if (members != null) {
            boolean stillElsewhere = sessionBindings.values().stream()
                    .anyMatch(b -> binding.policyId.equals(b.policyId)
                            && binding.email.equals(b.email));
            if (!stillElsewhere) {
                members.remove(binding.email);
            }
            if (members.isEmpty()) {
                roomMembers.remove(binding.policyId);
                members = Collections.emptyList();
            }
        } else {
            members = Collections.emptyList();
        }
        return new DisconnectResult(binding.policyId, new ArrayList<>(members));
    }

    public List<String> currentMembers(String policyId) {
        List<String> members = roomMembers.get(policyId);
        return members == null ? Collections.emptyList() : new ArrayList<>(members);
    }

    private record SessionBinding(String policyId, String email) { }

    public record DisconnectResult(String policyId, List<String> members) { }
}
