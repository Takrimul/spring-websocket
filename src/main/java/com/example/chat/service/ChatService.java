package com.example.chat.service;

import com.example.chat.model.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================
 * CHAT SERVICE — Validation and business rules
 * ============================================================
 *
 * Separated from the controller so business logic is testable
 * without spinning up a Spring WebSocket context.
 *
 * In a real app this service would also:
 *   - Persist messages to a database
 *   - Check room membership / ban lists
 *   - Apply rate limiting (max N messages/second per user)
 *   - Run profanity filters or content moderation
 *   - Handle message encryption
 */
@Service
public class ChatService {

    private static final int MAX_CONTENT_LENGTH = 2000; // characters
    private static final int ROOM_HISTORY_LIMIT = 100;
    private final Map<String, Deque<ChatMessage>> roomHistory = new ConcurrentHashMap<>();
    private final Map<String, Deque<ChatMessage>> pendingByUserRoom = new ConcurrentHashMap<>();

    /**
     * Validates an incoming ChatMessage.
     * Throws IllegalArgumentException if invalid — the controller's
     * @MessageExceptionHandler will catch it and send an error to the user.
     */
    public void validate(ChatMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Message payload cannot be null");
        }

        String content = message.getContent();

        // Content is required only for CHAT messages
        if (message.getType() == null || message.getType() == ChatMessage.Type.CHAT) {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Message content cannot be empty");
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException(
                        "Message exceeds maximum length of " + MAX_CONTENT_LENGTH + " characters"
                );
            }
        }

        // Room or recipient is required
        if ((message.getRoomId() == null || message.getRoomId().isBlank())
                && (message.getTo() == null || message.getTo().isBlank())) {
            throw new IllegalArgumentException("Message must have a roomId or recipient (to)");
        }
    }

    /**
     * Truncate content for safe logging — don't log full message content
     * in production (privacy and log bloat concerns).
     */
    public String sanitizeForLog(String content) {
        if (content == null) return "(null)";
        return content.length() > 50 ? content.substring(0, 47) + "..." : content;
    }

    public void storeRoomMessage(ChatMessage message) {
        if (message == null || message.getRoomId() == null || message.getRoomId().isBlank()) {
            return;
        }

        roomHistory.compute(message.getRoomId(), (roomId, queue) -> {
            Deque<ChatMessage> history = (queue != null) ? queue : new ArrayDeque<>();
            history.addLast(message);
            while (history.size() > ROOM_HISTORY_LIMIT) {
                history.removeFirst();
            }
            return history;
        });
    }

    public List<ChatMessage> getRecentRoomMessages(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return List.of();
        }
        Deque<ChatMessage> history = roomHistory.get(roomId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(history);
    }

    public void queuePendingMessage(String username, String roomId, ChatMessage message) {
        if (username == null || username.isBlank() || roomId == null || roomId.isBlank() || message == null) {
            return;
        }
        String key = username + "|" + roomId;
        pendingByUserRoom.compute(key, (k, queue) -> {
            Deque<ChatMessage> pending = (queue != null) ? queue : new ArrayDeque<>();
            pending.addLast(message);
            while (pending.size() > ROOM_HISTORY_LIMIT) {
                pending.removeFirst();
            }
            return pending;
        });
    }

    public List<ChatMessage> drainPendingMessages(String username, String roomId) {
        if (username == null || username.isBlank() || roomId == null || roomId.isBlank()) {
            return List.of();
        }
        String key = username + "|" + roomId;
        Deque<ChatMessage> pending = pendingByUserRoom.remove(key);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(pending);
    }
}