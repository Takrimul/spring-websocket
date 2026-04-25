package com.example.chat.events;

import com.example.chat.model.ChatMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

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

    /**
     * Room presence: which users are in each room.
     * roomId → Set of online usernames.
     * ConcurrentHashMap + ConcurrentHashMap.newKeySet() = thread-safe.
     */
    private final Map<String, Set<String>> roomPresence = new ConcurrentHashMap<>();

    /**
     * Session → room mapping so we know which room to update on disconnect.
     * A user can only be in one room per session in this demo.
     * In production, a user might subscribe to multiple rooms.
     */
    private final Map<String, String> sessionRoomMap = new ConcurrentHashMap<>();

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
            String username = principal.getName();
            System.out.printf("[EVENT] Connected: %s (session=%s)%n",
                    username, accessor.getSessionId());

            // Optionally send a welcome message to this user only
            // messagingTemplate.convertAndSendToUser(
            //     username, "/queue/notifications",
            //     ChatMessage.system("Welcome back, " + username + "!", null)
            // );
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

        String username = principal.getName();

        // Find which room this session was in and clean up
        String roomId = sessionRoomMap.remove(sessionId);
        if (roomId != null) {
            Set<String> presentUsers = roomPresence.get(roomId);
            if (presentUsers != null) {
                presentUsers.remove(username);
            }

            // Broadcast "left the room" to everyone remaining
            ChatMessage leaveMsg = ChatMessage.system(
                    username + " disconnected", roomId
            );
            messagingTemplate.convertAndSend(
                    "/topic/chat.room." + roomId, leaveMsg
            );
        }
    }

    // =========================================================================
    // SUBSCRIBED — track which room a session joined
    // =========================================================================

    /**
     * Fired when a client sends a STOMP SUBSCRIBE frame.
     * We use this to update our room presence map.
     *
     * If Bob subscribes to /topic/chat.room.42:
     *   - Add Bob to roomPresence["42"]
     *   - Map sessionId → "42" so we can clean up on disconnect
     *   - Broadcast "Bob joined" to room 42
     */
    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        String destination  = accessor.getDestination();
        String sessionId    = accessor.getSessionId();

        if (principal == null || destination == null) return;

        String username = principal.getName();

        // Only track room subscriptions, not user-private queues
        if (destination.startsWith("/topic/chat.room.")) {
            String roomId = destination.substring("/topic/chat.room.".length());

            // Add user to the room's presence set
            roomPresence
                    .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                    .add(username);

            // Track session → room for disconnect cleanup
            sessionRoomMap.put(sessionId, roomId);

            // Broadcast join notification
            ChatMessage joinMsg = ChatMessage.system(
                    username + " joined the room", roomId
            );
            messagingTemplate.convertAndSend(
                    "/topic/chat.room." + roomId, joinMsg
            );

            System.out.printf("[EVENT] %s subscribed to room:%s | online: %s%n",
                    username, roomId, roomPresence.get(roomId));
        }
    }

    // =========================================================================
    // PUBLIC API — other components can query presence
    // =========================================================================

    /** Returns the set of usernames currently online in a room. */
    public Set<String> getOnlineUsers(String roomId) {
        return roomPresence.getOrDefault(roomId, Set.of());
    }
}