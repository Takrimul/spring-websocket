package com.example.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {
    @Id
    private UUID id;

    @Column(name = "room_id", nullable = false, length = 100)
    private String roomId;

    @Column(name = "sender", nullable = false, length = 100)
    private String sender;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "encrypted", nullable = false)
    private boolean encrypted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
