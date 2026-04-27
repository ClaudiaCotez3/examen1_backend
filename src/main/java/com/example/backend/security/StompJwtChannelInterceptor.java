package com.example.backend.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

/**
 * Authenticates STOMP frames using the same JWT the REST endpoints accept.
 *
 * On CONNECT we read the {@code Authorization: Bearer …} header, validate
 * the token through {@link JwtService}, enforce that the user has the ADMIN
 * role (only admins can collaborate on diagrams) and attach a small
 * {@link CollabPrincipal} to the session so downstream {@code @MessageMapping}
 * handlers can resolve the user without a DB hit.
 *
 * Subsequent SEND/SUBSCRIBE frames re-use the principal stored on the
 * STOMP session — no per-frame token validation, that's what CONNECT is for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompJwtChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor
                .getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = extractBearerToken(accessor);
            if (token == null || !jwtService.isTokenValid(token) || jwtService.isExpired(token)) {
                throw new IllegalArgumentException("Invalid or missing JWT on STOMP CONNECT");
            }
            String role = jwtService.extractRole(token);
            if (!RoleName.ADMIN.equalsIgnoreCase(role)) {
                throw new IllegalArgumentException("Only ADMIN users can collaborate on diagrams");
            }
            String userId = jwtService.extractUserId(token);
            String email = jwtService.extractEmail(token);
            CollabPrincipal principal = new CollabPrincipal(userId, email == null ? userId : email);
            accessor.setUser(principal);
            log.info("STOMP CONNECT accepted: email={}, sessionId={}",
                    principal.getName(), accessor.getSessionId());
        }
        return message;
    }

    private String extractBearerToken(StompHeaderAccessor accessor) {
        List<String> values = accessor.getNativeHeader("Authorization");
        if (values == null || values.isEmpty()) {
            return null;
        }
        String first = values.get(0);
        if (first == null) return null;
        if (first.startsWith("Bearer ")) {
            return first.substring("Bearer ".length()).trim();
        }
        return first.trim();
    }

    /**
     * Minimal {@link Principal} attached to each STOMP session. Carries the
     * user id (as the principal name, used by Spring's STOMP routing) and
     * the email, which the collaboration controller broadcasts so other
     * admins can see who is editing.
     */
    public static final class CollabPrincipal implements Principal {
        private final String userId;
        private final String email;

        public CollabPrincipal(String userId, String email) {
            this.userId = userId;
            this.email = email;
        }

        @Override
        public String getName() {
            return userId;
        }

        public String getEmail() {
            return email;
        }
    }
}
