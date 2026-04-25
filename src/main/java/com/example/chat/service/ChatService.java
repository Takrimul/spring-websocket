package com.example.chat.service;

import com.example.chat.entity.DirectMessageEntity;
import com.example.chat.model.ChatMessage;
import com.example.chat.repository.DirectMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

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
    @Autowired
    private DirectMessageRepository directMessageRepository;

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

    public ChatMessage persistDirectMessage(String senderPhone, String receiverPhone, ChatMessage incomingMessage) {
        validate(incomingMessage);
        ChatMessage outgoing = ChatMessage.chat(senderPhone, receiverPhone, null, incomingMessage.getContent());
        outgoing.setEncrypted(Boolean.TRUE.equals(incomingMessage.getEncrypted()));

        DirectMessageEntity entity = new DirectMessageEntity();
        entity.setId(UUID.fromString(outgoing.getId()));
        entity.setSenderPhone(senderPhone);
        entity.setReceiverPhone(receiverPhone);
        entity.setContent(outgoing.getContent());
        entity.setEncrypted(Boolean.TRUE.equals(outgoing.getEncrypted()));
        entity.setStatus(DirectMessageEntity.Status.SENT.name());
        entity.setCreatedAt(outgoing.getTimestamp());
        directMessageRepository.save(entity);
        return outgoing;
    }

    public List<ChatMessage> getConversation(String userPhone, String peerPhone) {
        List<DirectMessageEntity> out = directMessageRepository
                .findTop100BySenderPhoneAndReceiverPhoneOrderByCreatedAtAsc(userPhone, peerPhone);
        List<DirectMessageEntity> in = directMessageRepository
                .findTop100ByReceiverPhoneAndSenderPhoneOrderByCreatedAtAsc(userPhone, peerPhone);
        List<DirectMessageEntity> merged = new ArrayList<>(out.size() + in.size());
        merged.addAll(out);
        merged.addAll(in);
        merged.sort(Comparator.comparing(DirectMessageEntity::getCreatedAt));
        List<ChatMessage> messages = new ArrayList<>();
        for (DirectMessageEntity entity : merged) {
            messages.add(toChatMessage(entity, false));
        }
        return messages;
    }

    public List<ChatMessage> getPendingForUser(String userPhone) {
        List<DirectMessageEntity> pending = directMessageRepository
                .findByReceiverPhoneAndStatusOrderByCreatedAtAsc(userPhone, DirectMessageEntity.Status.SENT.name());
        List<ChatMessage> result = new ArrayList<>();
        for (DirectMessageEntity entity : pending) {
            result.add(toChatMessage(entity, true));
        }
        return result;
    }

    public Optional<ChatMessage> markDelivered(String receiverPhone, String messageId) {
        Optional<DirectMessageEntity> maybe = directMessageRepository
                .findByIdAndReceiverPhone(UUID.fromString(messageId), receiverPhone);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        DirectMessageEntity entity = maybe.get();
        if (DirectMessageEntity.Status.SENT.name().equals(entity.getStatus())) {
            entity.setStatus(DirectMessageEntity.Status.DELIVERED.name());
            entity.setDeliveredAt(Instant.now());
            directMessageRepository.save(entity);
        }
        return Optional.of(ChatMessage.delivered(receiverPhone, messageId, null));
    }

    public Optional<ChatMessage> markSeen(String receiverPhone, String messageId) {
        Optional<DirectMessageEntity> maybe = directMessageRepository
                .findByIdAndReceiverPhone(UUID.fromString(messageId), receiverPhone);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        DirectMessageEntity entity = maybe.get();
        if (!DirectMessageEntity.Status.SEEN.name().equals(entity.getStatus())) {
            entity.setStatus(DirectMessageEntity.Status.SEEN.name());
            if (entity.getDeliveredAt() == null) {
                entity.setDeliveredAt(Instant.now());
            }
            entity.setSeenAt(Instant.now());
            directMessageRepository.save(entity);
        }
        return Optional.of(ChatMessage.seen(receiverPhone, messageId, null));
    }

    public Optional<String> findSenderByMessageId(String messageId) {
        return directMessageRepository.findById(UUID.fromString(messageId))
                .map(DirectMessageEntity::getSenderPhone);
    }

    private ChatMessage toChatMessage(DirectMessageEntity entity, boolean history) {
        ChatMessage message = new ChatMessage();
        message.setId(entity.getId().toString());
        message.setType(ChatMessage.Type.CHAT);
        message.setFrom(entity.getSenderPhone());
        message.setTo(entity.getReceiverPhone());
        message.setContent(entity.getContent());
        message.setEncrypted(entity.isEncrypted());
        message.setTimestamp(entity.getCreatedAt());
        message.setHistory(history);
        return message;
    }
}