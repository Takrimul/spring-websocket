package com.example.chat.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================
 * AUTH CHANNEL INTERCEPTOR
 * ============================================================
 *
 * Every STOMP frame flows through this interceptor on the inbound channel.
 * The interceptor runs BEFORE the broker or @MessageMapping controller
 * ever sees the frame.
 *
 * How the inbound channel pipeline works:
 *
 *   WebSocket frame arrives
 *         │
 *         ▼
 *   STOMP decoder (bytes → StompHeaderAccessor)
 *         │
 *         ▼
 *   ChannelInterceptor.preSend()   ← WE ARE HERE
 *         │
 *         ▼
 *   Message broker / @MessageMapping
 *
 * The CONNECT frame is the only place we receive authentication headers
 * from the client. We extract the token, validate it (or fake-validate
 * in this demo), and set a Principal on the session attributes.
 *
 * Spring then automatically propagates that Principal to every subsequent
 * SEND/SUBSCRIBE frame from the same session.
 *
 * In production: replace the dummy validation with JWT parsing
 * using java-jwt or spring-security-oauth2-resource-server.
 */
@Component
public class AuthChannelInterceptor implements ChannelInterceptor {
    @Value("${chat.security.jwt-secret}")
    private String jwtSecret;

    @Value("${chat.security.allow-dev-token:true}")
    private boolean allowDevToken;

    /**
     * In-memory session store: sessionId → username.
     * In a clustered deployment, use Redis instead.
     *
     * ConcurrentHashMap is thread-safe for concurrent WebSocket sessions.
     */
    private final Map<String, String> sessionUserMap = new ConcurrentHashMap<>();

    /**
     * preSend() is called for EVERY inbound STOMP frame.
     * Return null to DROP the frame (silently reject).
     * Return the message to continue processing.
     * Throw an exception to send an ERROR frame to the client.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // StompHeaderAccessor gives us structured access to STOMP headers
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class
        );

        if (accessor == null) {
            return message; // Not a STOMP frame, pass through
        }

        StompCommand command = accessor.getCommand();

        // ── CONNECT frame: client is opening a new STOMP session ──────────────
        if (StompCommand.CONNECT.equals(command)) {
            // The client sends: CONNECT\nAuthorization:Bearer <token>\n\n\0
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            String username   = extractUsername(authHeader);

            if (username == null) {
                // Returning null drops the frame → client gets a socket close
                // Or throw to send an ERROR frame:
                throw new IllegalArgumentException(
                        "Missing or invalid Authorization header"
                );
            }

            // Build a Spring Security Principal from the validated username.
            // Spring uses this Principal to:
            //   1. Set accessor.getUser() on all future frames
            //   2. Route /user/{username}/... destinations correctly
            //   3. Power @SendToUser in controllers
            Principal principal = new UsernamePasswordAuthenticationToken(
                    username, null, Collections.emptyList()
            );
            accessor.setUser(principal);

            // Track the session so we can clean up on DISCONNECT
            sessionUserMap.put(accessor.getSessionId(), username);

            System.out.println("[WS] CONNECTED: " + username
                    + " | session=" + accessor.getSessionId());
        }

        // ── DISCONNECT frame: client is closing gracefully ─────────────────────
        else if (StompCommand.DISCONNECT.equals(command)) {
            String username = sessionUserMap.remove(accessor.getSessionId());
            System.out.println("[WS] DISCONNECTED: " + username
                    + " | session=" + accessor.getSessionId());
        }

        // ── SUBSCRIBE frame: client subscribing to a topic ────────────────────
        else if (StompCommand.SUBSCRIBE.equals(command)) {
            String destination = accessor.getDestination();
            String user = getUsername(accessor);
            System.out.println("[WS] SUBSCRIBE: " + user
                    + " → " + destination);
            // Here you could enforce authorization:
            // e.g., ensure user can only subscribe to rooms they're a member of
        }

        return message; // Continue processing
    }

    /**
     * Validate the Authorization header and extract a username.
     *
     * Real implementation: parse a JWT, verify signature, extract subject.
     * Demo: treat "Bearer <username>" as valid (never do this in production!).
     */
    private String extractUsername(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String subject = claims.getSubject();
            return (subject == null || subject.isBlank()) ? null : subject;
        } catch (Exception ignored) {
            // Dev-only fallback token mode: "Bearer alice"
            if (allowDevToken) {
                return token;
            }
            return null;
        }
    }

    /**
     * Safely get the username from the current STOMP accessor.
     * After CONNECT, accessor.getUser() is non-null for all frames.
     */
    private String getUsername(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        return (user != null) ? user.getName() : "anonymous";
    }
}