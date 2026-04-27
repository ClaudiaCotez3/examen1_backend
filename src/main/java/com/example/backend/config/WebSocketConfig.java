package com.example.backend.config;

import com.example.backend.security.StompJwtChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Real-time collaboration channel for the policy designer. Exposes a STOMP
 * endpoint at {@code /ws/policies} guarded by JWT (header in the CONNECT
 * frame, validated by {@link StompJwtChannelInterceptor}).
 *
 * Topics used:
 *   /topic/policies/{id}/diagram   — full BPMN XML broadcast on every change
 *   /topic/policies/{id}/cursor    — remote cursor positions
 *   /topic/policies/{id}/presence  — list of admins currently in the room
 *
 * Inbound destinations (under /app):
 *   /app/policies/{id}/diagram     — push a new XML version
 *   /app/policies/{id}/cursor      — push the local cursor coordinates
 *   /app/policies/{id}/join        — announce the local admin joining
 *   /app/policies/{id}/leave       — announce the local admin leaving
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompJwtChannelInterceptor jwtInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry
                // Native STOMP/WebSocket — frontend uses @stomp/stompjs.
                .addEndpoint("/ws/policies")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtInterceptor);
    }
}
