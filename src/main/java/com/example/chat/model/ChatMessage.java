package com.example.chat.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * ============================================================
 * MESSAGE MODELS — Every payload type over the wire
 * ============================================================
 *
 * These are serialised to JSON and packed into STOMP TEXT frames.
 * A STOMP frame carrying one of these looks like:
 *
 *   MESSAGE
 *   destination:/topic/chat.room.42
 *   content-type:application/json
 *   message-id:abc-123
 *   subscription:sub-0
 *
 *   {"type":"CHAT","id":"...","from":"Alice","content":"Hi!","timestamp":"..."}
 *   \0
 *
 * The \0 (null byte) is the STOMP frame terminator.
 * The broker writes all these headers automatically.
 *
 * @JsonInclude(NON_NULL) means null fields are omitted from JSON.
 * This keeps payloads small — a TYPING event doesn't need "content".
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    /**
     * Message types define what the receiver should do with the payload.
     * A single shared type enum keeps client/server in sync.
     */
    public enum Type {
        /** Normal chat message */
        CHAT,
        /** Recipient device received message */
        DELIVERED,
        /** Sender started typing */
        TYPING,
        /** Sender stopped typing */
        STOP_TYPING,
        /** Recipient has seen the message */
        SEEN,
        /** System notification (join/leave) */
        SYSTEM,
        /** Server-side error feedback */
        ERROR
    }

    private String id;           // UUID, used for deduplication and seen-receipts
    private Type type;           // What kind of event this is
    private String from;         // Sender username (set server-side from Principal)
    private String to;           // Recipient username (for DMs) or room ID
    private String content;      // Message text (null for TYPING, SEEN, etc.)
    private Boolean encrypted;   // true if content is ciphertext
    private String roomId;       // Chat room identifier
    private String seenMessageId;// For SEEN events: which message was seen
    private String deliveredMessageId; // For DELIVERED events: which message was delivered
    private Boolean history;     // true if replayed from server-side room history
    private Instant timestamp;   // Server-assigned, prevents client clock skew
    private String errorCode;    // For ERROR type: machine-readable error key
    private String errorDetail;  // For ERROR type: human-readable description

    // ── Constructors ──────────────────────────────────────────────────────────

    public ChatMessage() {}

    /** Factory: build a chat message */
    public static ChatMessage chat(String from, String to, String roomId, String content) {
        ChatMessage m = new ChatMessage();
        m.id          = UUID.randomUUID().toString();
        m.type        = Type.CHAT;
        m.from        = from;
        m.to          = to;
        m.roomId      = roomId;
        m.content     = content;
        m.encrypted   = false;
        m.timestamp   = Instant.now();
        return m;
    }

    /** Factory: typing indicator */
    public static ChatMessage typing(String from, String roomId, boolean isTyping) {
        ChatMessage m = new ChatMessage();
        m.id        = UUID.randomUUID().toString();
        m.type      = isTyping ? Type.TYPING : Type.STOP_TYPING;
        m.from      = from;
        m.roomId    = roomId;
        m.timestamp = Instant.now();
        return m;
    }

    /** Factory: seen receipt */
    public static ChatMessage seen(String from, String seenMessageId, String roomId) {
        ChatMessage m  = new ChatMessage();
        m.id           = UUID.randomUUID().toString();
        m.type         = Type.SEEN;
        m.from         = from;
        m.seenMessageId = seenMessageId;
        m.roomId       = roomId;
        m.timestamp    = Instant.now();
        return m;
    }

    /** Factory: delivered receipt */
    public static ChatMessage delivered(String from, String deliveredMessageId, String roomId) {
        ChatMessage m = new ChatMessage();
        m.id = UUID.randomUUID().toString();
        m.type = Type.DELIVERED;
        m.from = from;
        m.deliveredMessageId = deliveredMessageId;
        m.roomId = roomId;
        m.timestamp = Instant.now();
        return m;
    }

    /** Factory: system notification */
    public static ChatMessage system(String content, String roomId) {
        ChatMessage m = new ChatMessage();
        m.id        = UUID.randomUUID().toString();
        m.type      = Type.SYSTEM;
        m.content   = content;
        m.roomId    = roomId;
        m.timestamp = Instant.now();
        return m;
    }

    /** Factory: error message (goes to individual user, not broadcast) */
    public static ChatMessage error(String code, String detail) {
        ChatMessage m = new ChatMessage();
        m.id          = UUID.randomUUID().toString();
        m.type        = Type.ERROR;
        m.errorCode   = code;
        m.errorDetail = detail;
        m.timestamp   = Instant.now();
        return m;
    }

    @Override
    public String toString() {
        return "ChatMessage{type=" + type + ", from='" + from + "', content='" + content + "'}";
    }
}