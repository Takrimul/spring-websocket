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
@Table(name = "dm_messages")
public class DirectMessageEntity {
    public enum Status {
        SENT,
        DELIVERED,
        SEEN
    }

    @Id
    private UUID id;

    @Column(name = "sender_phone", nullable = false, length = 20)
    private String senderPhone;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "encrypted", nullable = false)
    private boolean encrypted;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "seen_at")
    private Instant seenAt;
}
