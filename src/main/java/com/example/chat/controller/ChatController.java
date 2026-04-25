package com.example.chat.controller;

import com.example.chat.events.WebSocketEventListener;
import com.example.chat.model.ChatMessage;
import com.example.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Set;

/**
 * ============================================================
 * CHAT CONTROLLER — Business logic for every STOMP message
 * ============================================================
 *
 * How routing works end-to-end:
 *
 *   Client sends STOMP frame:
 *     SEND
 *     destination:/app/chat.send
 *     ...
 *     {"content":"hello","roomId":"42"}\0
 *
 *   Spring sees "/app" prefix → strips it → looks for
 *   @MessageMapping("/chat.send") → calls sendMessage()
 *
 *   The method returns a ChatMessage, Spring serialises it to JSON,
 *   and delivers it to the @SendTo destination.
 *
 * Two delivery strategies:
 *
 *   @SendTo("/topic/chat.room.{roomId}")
 *     → Broadcasts to ALL subscribers of that topic.
 *     → The broker fans it out. Zero extra code needed.
 *
 *   SimpMessagingTemplate.convertAndSendToUser(username, "/queue/...", payload)
 *     → Delivers only to the user whose Principal.getName() == username.
 *     → Spring translates to /user/{username}/queue/...
 *     → Only that user's session(s) receive it.
 *
 * Headers the client must set when subscribing:
 *   /topic/chat.room.42          → group messages in room 42
 *   /user/queue/messages         → private DMs for this user
 *   /user/queue/notifications    → typing, seen, errors
 */
@Controller
public class ChatController {

    /**
     * SimpMessagingTemplate is Spring's programmatic way to send
     * messages to any destination — useful when you can't use @SendTo
     * (e.g., when the destination depends on runtime state).
     *
     * Under the hood it serialises your object to JSON and wraps it
     * in a STOMP MESSAGE frame targeting the given destination.
     */
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatService chatService;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    // =========================================================================
    // 1. SEND CHAT MESSAGE
    // =========================================================================

    /**
     * Handles incoming chat messages from any user.
     *
     * Flow:
     *   Alice sends  → /app/chat.send
     *   Server validates, persists, stamps
     *   → broadcasts to  /topic/chat.room.{roomId}   (everyone in the room)
     *   → sends receipt  /user/alice/queue/messages   (delivery confirmation)
     *
     * @param incomingMessage — deserialized from the STOMP frame payload (JSON)
     * @param headerAccessor  — gives us session attributes (sessionId etc.)
     * @param principal       — the authenticated user (set by AuthChannelInterceptor)
     *
     * @return the enriched ChatMessage → Spring sends it to @SendTo destination
     */
    @MessageMapping("/chat.send/{roomId}")
    @SendTo("/topic/chat.room.{roomId}")
    public ChatMessage sendMessage(
            @Payload ChatMessage incomingMessage,
            @DestinationVariable String roomId,
            SimpMessageHeaderAccessor headerAccessor,
            Principal principal) {

        // The Principal is guaranteed non-null because AuthChannelInterceptor
        // validated the CONNECT frame. If somehow null, reject the message.
        if (principal == null) {
            throw new IllegalStateException("Unauthenticated message received");
        }

        // ALWAYS set the sender server-side from the authenticated Principal.
        // Never trust client-provided "from" — clients can spoof anything.
        String senderUsername = principal.getName();

        // Validate the message content
        chatService.validate(incomingMessage);

        // Build the enriched message (adds server-assigned ID, timestamp, etc.)
        ChatMessage outgoing = ChatMessage.chat(
                senderUsername,
                incomingMessage.getTo(),
                roomId,
                incomingMessage.getContent()
        );

        // Optionally persist to database here:
        // messageRepository.save(outgoing);

        System.out.printf("[MSG] %s → room:%s : %s%n",
                senderUsername, roomId, incomingMessage.getContent());

        chatService.storeRoomMessage(outgoing);
        queueForOfflineRoomMembers(outgoing, senderUsername, roomId);

        // Return value is automatically sent to /topic/chat.room.{roomId}
        // All subscribers (including Alice herself) receive it.
        return outgoing;
    }

    @MessageMapping("/chat.history/{roomId}")
    public void sendRoomHistory(@DestinationVariable String roomId, Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Unauthenticated");
        }

        String username = principal.getName();
        for (ChatMessage pending : chatService.drainPendingMessages(username, roomId)) {
            ChatMessage replay = toReplayMessage(pending);
            messagingTemplate.convertAndSendToUser(username, "/queue/history", replay);
        }

        for (ChatMessage message : chatService.getRecentRoomMessages(roomId)) {
            ChatMessage replay = toReplayMessage(message);
            messagingTemplate.convertAndSendToUser(username, "/queue/history", replay);
        }
    }

    // =========================================================================
    // 2. DIRECT MESSAGE (private chat between two users)
    // =========================================================================

    /**
     * Sends a private message to a specific user.
     *
     * Flow:
     *   Alice sends  → /app/chat.dm
     *   Server routes → /user/bob/queue/messages     (Bob's private queue)
     *                 → /user/alice/queue/messages    (Alice's own copy/receipt)
     *
     * convertAndSendToUser(username, destination, payload):
     *   Spring prepends "/user/{username}" to the destination, so the actual
     *   STOMP destination becomes "/user/bob/queue/messages".
     *   Bob must subscribe to "/user/queue/messages" — Spring adds the prefix.
     */
    @MessageMapping("/chat.dm")
    public void sendDirectMessage(@Payload ChatMessage incomingMessage,
                                  Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Unauthenticated");
        }

        chatService.validate(incomingMessage);
        String sender    = principal.getName();
        String recipient = incomingMessage.getTo();

        ChatMessage outgoing = ChatMessage.chat(
                sender, recipient, null, incomingMessage.getContent()
        );

        // Deliver to recipient's private queue
        messagingTemplate.convertAndSendToUser(
                recipient,
                "/queue/messages",
                outgoing
        );

        // Deliver echo/receipt back to sender
        messagingTemplate.convertAndSendToUser(
                sender,
                "/queue/messages",
                outgoing
        );

        System.out.printf("[DM] %s → %s%n", sender, recipient);
    }

    // =========================================================================
    // 3. TYPING INDICATOR
    // =========================================================================

    /**
     * Real-time "Alice is typing..." indicator.
     *
     * This is intentionally lightweight — no persistence, fire-and-forget.
     * The payload just needs to carry: who is typing, in which room.
     *
     * Because typing events come in bursts (every keypress), they go through
     * the same STOMP channel but the client should debounce: send TYPING on
     * first keystroke, STOP_TYPING after 3s of silence.
     *
     * @SendTo broadcasts to everyone in the room (including the sender,
     * who can ignore it client-side by checking if from === self).
     */
    @MessageMapping("/chat.typing/{roomId}")
    @SendTo("/topic/chat.room.{roomId}")
    public ChatMessage handleTyping(
            @Payload ChatMessage event,
            @DestinationVariable String roomId,
            Principal principal) {

        if (principal == null) throw new IllegalStateException("Unauthenticated");

        boolean isTyping = event.getType() != ChatMessage.Type.STOP_TYPING;

        ChatMessage typingEvent = ChatMessage.typing(
                principal.getName(), roomId, isTyping
        );

        System.out.printf("[TYPING] %s in room:%s → %s%n",
                principal.getName(), roomId, isTyping ? "typing" : "stopped");

        return typingEvent; // broadcast to /topic/chat.room.{roomId}
    }

    // =========================================================================
    // 4. SEEN RECEIPT
    // =========================================================================

    /**
     * Bob has read Alice's message — notify Alice that her message was seen.
     *
     * Flow:
     *   Bob sends   → /app/chat.seen  { seenMessageId: "abc-123" }
     *   Server routes → /user/alice/queue/notifications  (Alice's private channel)
     *
     * Alice's client receives { type:"SEEN", seenMessageId:"abc-123" }
     * and marks the message with two blue ticks (WhatsApp-style).
     *
     * We look up the original message author (Alice) from the seenMessageId.
     * In this demo we read it from the payload's "to" field; in production
     * query your message store: messageRepository.findById(seenMessageId).getSender()
     */
    @MessageMapping("/chat.seen")
    public void handleSeen(@Payload ChatMessage event, Principal principal) {
        if (principal == null) throw new IllegalStateException("Unauthenticated");

        String reader      = principal.getName(); // Bob
        String originalSender = event.getTo();   // Alice (who sent the message)
        String seenMsgId   = event.getSeenMessageId();

        if (seenMsgId == null || seenMsgId.isBlank()) {
            sendErrorToUser(reader, "INVALID_SEEN", "seenMessageId is required");
            return;
        }

        ChatMessage seenReceipt = ChatMessage.seen(reader, seenMsgId, event.getRoomId());
        seenReceipt.setTo(originalSender);

        // Notify the original sender that their message was seen
        messagingTemplate.convertAndSendToUser(
                originalSender,
                "/queue/notifications",
                seenReceipt
        );
        // Fallback path: also publish to room topic; target user filters client-side.
        messagingTemplate.convertAndSend("/topic/chat.room." + event.getRoomId(), seenReceipt);

        System.out.printf("[SEEN] %s saw message:%s (by %s)%n",
                reader, seenMsgId, originalSender);
    }

    @MessageMapping("/chat.delivered")
    public void handleDelivered(@Payload ChatMessage event, Principal principal) {
        if (principal == null) throw new IllegalStateException("Unauthenticated");

        String receiver = principal.getName();
        String originalSender = event.getTo();
        String deliveredMsgId = event.getDeliveredMessageId();

        if (deliveredMsgId == null || deliveredMsgId.isBlank()) {
            sendErrorToUser(receiver, "INVALID_DELIVERED", "deliveredMessageId is required");
            return;
        }

        ChatMessage deliveredReceipt = ChatMessage.delivered(receiver, deliveredMsgId, event.getRoomId());
        deliveredReceipt.setTo(originalSender);
        messagingTemplate.convertAndSendToUser(
                originalSender,
                "/queue/notifications",
                deliveredReceipt
        );
        // Fallback path: also publish to room topic; target user filters client-side.
        messagingTemplate.convertAndSend("/topic/chat.room." + event.getRoomId(), deliveredReceipt);
        System.out.printf("[DELIVERED] %s received message:%s (by %s)%n",
                receiver, deliveredMsgId, originalSender);
    }

    // =========================================================================
    // 5. USER JOIN / LEAVE (triggered by session lifecycle events)
    // =========================================================================

    /**
     * Announce that a user has joined a room.
     * Called when the user sends a JOIN frame (not on connect — a user
     * can be connected but not in any room).
     */
    @MessageMapping("/chat.join/{roomId}")
    @SendTo("/topic/chat.room.{roomId}")
    public ChatMessage handleJoin(
            @DestinationVariable String roomId,
            Principal principal) {

        if (principal == null) throw new IllegalStateException("Unauthenticated");

        System.out.printf("[JOIN] %s joined room:%s%n", principal.getName(), roomId);
        return ChatMessage.system(principal.getName() + " joined the room", roomId);
    }

    /**
     * Announce that a user has left a room.
     */
    @MessageMapping("/chat.leave/{roomId}")
    @SendTo("/topic/chat.room.{roomId}")
    public ChatMessage handleLeave(
            @DestinationVariable String roomId,
            Principal principal) {

        if (principal == null) throw new IllegalStateException("Unauthenticated");

        System.out.printf("[LEAVE] %s left room:%s%n", principal.getName(), roomId);
        return ChatMessage.system(principal.getName() + " left the room", roomId);
    }

    // =========================================================================
    // 6. ERROR HANDLING — @MessageExceptionHandler
    // =========================================================================

    /**
     * Handles any exception thrown inside this controller.
     *
     * @SendToUser routes the error ONLY to the user who caused it.
     * Everyone else is unaffected — broadcast channels stay clean.
     *
     * The error lands at /user/{username}/queue/errors on the client.
     * The client subscribes to /user/queue/errors to receive these.
     *
     * Exception types handled:
     *   IllegalArgumentException → VALIDATION_ERROR  (bad client input)
     *   IllegalStateException    → AUTH_ERROR         (auth issues)
     *   Exception                → INTERNAL_ERROR     (catch-all)
     */
    @MessageExceptionHandler(IllegalArgumentException.class)
    @SendToUser("/queue/errors")
    public ChatMessage handleValidationError(IllegalArgumentException ex, Principal principal) {
        System.err.printf("[ERROR] Validation error for %s: %s%n",
                principal != null ? principal.getName() : "unknown", ex.getMessage());
        return ChatMessage.error("VALIDATION_ERROR", ex.getMessage());
    }

    @MessageExceptionHandler(IllegalStateException.class)
    @SendToUser("/queue/errors")
    public ChatMessage handleStateError(IllegalStateException ex) {
        System.err.printf("[ERROR] State error: %s%n", ex.getMessage());
        return ChatMessage.error("AUTH_ERROR", ex.getMessage());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ChatMessage handleGenericError(Exception ex, Principal principal) {
        System.err.printf("[ERROR] Unhandled exception for %s: %s%n",
                principal != null ? principal.getName() : "unknown", ex.getMessage());
        // Don't leak stack traces to clients in production
        return ChatMessage.error("INTERNAL_ERROR", "An unexpected error occurred");
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Programmatically send an error to a specific user's private error queue.
     * Use when you want to report an error in a void method (can't use @SendToUser).
     */
    private void sendErrorToUser(String username, String code, String detail) {
        messagingTemplate.convertAndSendToUser(
                username,
                "/queue/errors",
                ChatMessage.error(code, detail)
        );
    }

    private void queueForOfflineRoomMembers(ChatMessage outgoing, String senderUsername, String roomId) {
        Set<String> roomMembers = webSocketEventListener.getRoomMembers(roomId);
        Set<String> onlineUsers = webSocketEventListener.getOnlineUsers(roomId);
        for (String member : roomMembers) {
            if (member == null || member.equals(senderUsername)) {
                continue;
            }
            if (!onlineUsers.contains(member)) {
                chatService.queuePendingMessage(member, roomId, outgoing);
            }
        }
    }

    private ChatMessage toReplayMessage(ChatMessage message) {
        ChatMessage replay = new ChatMessage();
        replay.setId(message.getId());
        replay.setType(message.getType());
        replay.setFrom(message.getFrom());
        replay.setTo(message.getTo());
        replay.setContent(message.getContent());
        replay.setRoomId(message.getRoomId());
        replay.setSeenMessageId(message.getSeenMessageId());
        replay.setDeliveredMessageId(message.getDeliveredMessageId());
        replay.setTimestamp(message.getTimestamp());
        replay.setHistory(true);
        return replay;
    }
}