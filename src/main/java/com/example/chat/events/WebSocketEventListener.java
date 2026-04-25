package com.example.chat.events;

import com.example.chat.model.ChatMessage;
import com.example.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================
 * WEBSOCKET SESSION EVENT LISTENER
 * ============================================================
 *
 * Spring publishes lifecycle events for WebSocket sessions:
 *
 *   SessionConnectedEvent   → TCP+STOMP handshake complete
 *   SessionDisconnectEvent  → Session closed (normal or error)
 *   SessionSubscribeEvent   → Client subscribed to a destination
 *   SessionUnsubscribeEvent → Client unsubscribed
 *
 * These events are fired OUTSIDE of the STOMP message channel,
 * so they bypass the ChannelInterceptor. The session Principal
 * is still available via accessor.getUser().
 *
 * Why use events vs @MessageMapping for join/leave?
 *
 *   @MessageMapping("/chat.join") requires the client to explicitly
 *   send a frame. The client might crash and never send it.
 *
 *   SessionDisconnectEvent fires unconditionally on ANY disconnect —
 *   crash, network drop, browser close, server timeout. This is
 *   the reliable way to do presence tracking.
 *
 * Room tracking:
 *   We maintain a simple in-memory map of roomId → Set<username>.
 *   In production, use Redis Sets for distributed presence.
 */
@Component
public class WebSocketEventListener {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatService chatService;

    private final Map<String, Set<String>> onlineSessionsByPhone = new ConcurrentHashMap<>();

    // =========================================================================
    // CONNECTED
    // =========================================================================

    /**
     * Fired after the STOMP CONNECTED frame is sent back to the client.
     * At this point authentication is complete (our interceptor ran on CONNECT).
     *
     * Good place to:
     *   - Log connection metrics
     *   - Send a welcome message to the user's private queue
     *   - Update a presence/online-users store
     */
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();

        if (principal != null) {
            String phone = principal.getName();
            String sessionId = accessor.getSessionId();
            onlineSessionsByPhone.computeIfAbsent(phone, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);

            // Replay unread messages on reconnect/login.
            for (ChatMessage message : chatService.getPendingForUser(phone)) {
                messagingTemplate.convertAndSendToUser(phone, "/queue/messages", message);
            }
        }
    }

    // =========================================================================
    // DISCONNECTED (most important — handles crashes and drops)
    // =========================================================================

    /**
     * Fired when a WebSocket session ends for ANY reason:
     *   - Client called stompClient.disconnect()
     *   - Browser tab closed
     *   - Network timeout / internet dropped
     *   - Server killed the session
     *
     * CloseStatus codes you'll see:
     *   1000 = normal closure
     *   1001 = going away (browser navigating)
     *   1006 = abnormal closure (network drop — no close frame sent)
     *   1011 = server error
     *
     * This is where you update presence — remove the user from whatever
     * room they were in and broadcast "Alice left the room".
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        String sessionId   = accessor.getSessionId();

        System.out.printf("[EVENT] Disconnected: %s (session=%s, status=%s)%n",
                principal != null ? principal.getName() : "unknown",
                sessionId,
                event.getCloseStatus());

        if (principal == null) return;

        String phone = principal.getName();
        Set<String> sessions = onlineSessionsByPhone.get(phone);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                onlineSessionsByPhone.remove(phone);
            }
        }
    }

    public boolean isOnline(String phone) {
        Set<String> sessions = onlineSessionsByPhone.get(phone);
        return sessions != null && !sessions.isEmpty();
    }
}