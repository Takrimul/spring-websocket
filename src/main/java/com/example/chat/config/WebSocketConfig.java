package com.example.chat.config;

import com.example.chat.security.AuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

/**
 * ============================================================
 * WEBSOCKET CONFIGURATION — The heart of the setup
 * ============================================================
 *
 * @EnableWebSocketMessageBroker tells Spring to:
 *   1. Open a WebSocket endpoint at /ws
 *   2. Spin up an in-memory STOMP message broker
 *   3. Route STOMP frames to @MessageMapping methods
 *
 * What happens at runtime:
 *   Client connects → TCP 3-way handshake → HTTP GET /ws with
 *   "Upgrade: websocket" header → Spring replies "101 Switching Protocols"
 *   → HTTP is gone → STOMP frames flow over the raw WebSocket connection.
 *
 * STOMP frame anatomy:
 *   SEND\n
 *   destination:/app/chat.send\n
 *   content-type:application/json\n
 *   \n
 *   {"content":"hello"}\0
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private AuthChannelInterceptor authChannelInterceptor;

    /**
     * Step 1 — Register the WebSocket endpoint.
     *
     * Clients connect to ws://localhost:8080/ws
     * SockJS enables HTTP long-polling as a fallback for browsers that
     * don't support WebSocket (rare today, but production-safe).
     *
     * setAllowedOriginPatterns("*") = accept any origin in dev.
     * In production, lock this down to your domain:
     *   .setAllowedOrigins("https://yourapp.com")
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry
                .addEndpoint("/ws")                     // WebSocket URL
                .setAllowedOriginPatterns("*")          // CORS — restrict in prod
                .withSockJS();                          // Enable SockJS fallback
    }

    /**
     * Step 2 — Configure the message broker.
     *
     * Destination prefixes:
     *
     *  /app/**     → goes to @MessageMapping methods in controllers
     *               (your business logic runs here, then you publish back)
     *
     *  /topic/**   → broadcast channel — all subscribers receive the message
     *               e.g. /topic/chat.room.42  →  everyone in room 42
     *
     *  /queue/**   → point-to-point, typically one consumer
     *
     *  /user/**    → converted to /user/{userId}/** per-user delivery
     *               Spring prefixes the username automatically when you
     *               call convertAndSendToUser()
     *
     * The in-memory broker is fine for a single server. For multiple
     * Spring Boot instances, replace with a real broker like RabbitMQ:
     *   registry.enableStompBrokerRelay("/topic", "/queue")
     *           .setRelayHost("rabbitmq-host")
     *           .setRelayPort(61613);
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Messages starting with /app go to @MessageMapping controllers
        registry.setApplicationDestinationPrefixes("/app");

        // Messages starting with /topic or /queue go straight to broker
        // (bypassing controllers — for server-to-client push, or broker relay)
        registry.enableSimpleBroker("/topic", "/queue", "/user");

        // Used internally by convertAndSendToUser() to prefix user queues
        // e.g. /user/bob/queue/messages becomes the delivery address for Bob
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Step 3 — Intercept inbound STOMP channel.
     *
     * Every STOMP frame (CONNECT, SEND, SUBSCRIBE) passes through this
     * interceptor BEFORE being processed. We use it to:
     *   - Extract JWT from the CONNECT frame's Authorization header
     *   - Set the authenticated Principal on the session
     *   - Reject unauthenticated connections
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}