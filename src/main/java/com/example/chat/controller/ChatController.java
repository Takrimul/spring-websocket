package com.example.chat.controller;

import com.example.chat.model.ChatMessage;
import com.example.chat.service.AuthService;
import com.example.chat.service.ChatService;
import com.example.chat.service.PhoneService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;

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
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatService chatService;

    @Autowired
    private AuthService authService;

    @Autowired
    private PhoneService phoneService;

    @MessageMapping("/chat.dm")
    public void sendDirectMessage(@Payload ChatMessage incomingMessage, Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("Unauthenticated");
        }
        String senderPhone = phoneService.normalize(principal.getName());
        String receiverPhone = phoneService.normalize(incomingMessage.getTo());
        if (!authService.userExists(receiverPhone)) {
            sendErrorToUser(senderPhone, "UNKNOWN_RECEIVER", "Receiver phone does not exist");
            return;
        }
        ChatMessage outgoing = chatService.persistDirectMessage(senderPhone, receiverPhone, incomingMessage);
        messagingTemplate.convertAndSendToUser(receiverPhone, "/queue/messages", outgoing);
        messagingTemplate.convertAndSendToUser(senderPhone, "/queue/messages", outgoing);
    }

    @MessageMapping("/chat.sync")
    public void syncConversation(@Payload Map<String, String> payload, Principal principal) {
        if (principal == null) throw new IllegalStateException("Unauthenticated");
        String me = phoneService.normalize(principal.getName());
        String withPhone = phoneService.normalize(payload.get("withPhone"));
        if (!authService.userExists(withPhone)) {
            sendErrorToUser(me, "UNKNOWN_RECEIVER", "Receiver phone does not exist");
            return;
        }
        for (ChatMessage msg : chatService.getConversation(me, withPhone)) {
            msg.setHistory(true);
            messagingTemplate.convertAndSendToUser(me, "/queue/messages", msg);
        }
    }

    @MessageMapping("/chat.seen")
    public void handleSeen(@Payload ChatMessage event, Principal principal) {
        if (principal == null) throw new IllegalStateException("Unauthenticated");
        String reader = phoneService.normalize(principal.getName());
        String messageId = event.getSeenMessageId();
        if (messageId == null || messageId.isBlank()) {
            sendErrorToUser(reader, "INVALID_SEEN", "seenMessageId is required");
            return;
        }
        Optional<String> sender = chatService.findSenderByMessageId(messageId);
        if (sender.isEmpty()) return;
        Optional<ChatMessage> receipt = chatService.markSeen(reader, messageId);
        if (receipt.isEmpty()) return;
        ChatMessage seenReceipt = receipt.get();
        seenReceipt.setTo(sender.get());
        messagingTemplate.convertAndSendToUser(sender.get(), "/queue/notifications", seenReceipt);
    }

    @MessageMapping("/chat.delivered")
    public void handleDelivered(@Payload ChatMessage event, Principal principal) {
        if (principal == null) throw new IllegalStateException("Unauthenticated");
        String receiver = phoneService.normalize(principal.getName());
        String messageId = event.getDeliveredMessageId();
        if (messageId == null || messageId.isBlank()) {
            sendErrorToUser(receiver, "INVALID_DELIVERED", "deliveredMessageId is required");
            return;
        }
        Optional<String> sender = chatService.findSenderByMessageId(messageId);
        if (sender.isEmpty()) return;
        Optional<ChatMessage> receipt = chatService.markDelivered(receiver, messageId);
        if (receipt.isEmpty()) return;
        ChatMessage deliveredReceipt = receipt.get();
        deliveredReceipt.setTo(sender.get());
        messagingTemplate.convertAndSendToUser(sender.get(), "/queue/notifications", deliveredReceipt);
    }

    @MessageExceptionHandler(IllegalArgumentException.class)
    @SendToUser("/queue/errors")
    public ChatMessage handleValidationError(IllegalArgumentException ex, Principal principal) {
        return ChatMessage.error("VALIDATION_ERROR", ex.getMessage());
    }

    @MessageExceptionHandler(IllegalStateException.class)
    @SendToUser("/queue/errors")
    public ChatMessage handleStateError(IllegalStateException ex) {
        return ChatMessage.error("AUTH_ERROR", ex.getMessage());
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser("/queue/errors")
    public ChatMessage handleGenericError(Exception ex, Principal principal) {
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
}