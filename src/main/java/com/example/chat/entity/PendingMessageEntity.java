package com.example.chat.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Table(name = "pending_messages")
public class PendingMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "room_id", nullable = false, length = 100)
    private String roomId;

    @Column(name = "recipient", nullable = false, length = 100)
    private String recipient;

    @Column(name = "sender", nullable = false, length = 100)
    private String sender;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "encrypted", nullable = false)
    private boolean encrypted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

}
