package com.example.chat.repository;

import com.example.chat.entity.PendingMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingMessageRepository extends JpaRepository<PendingMessageEntity, Long> {
    List<PendingMessageEntity> findByRecipientAndRoomIdOrderByCreatedAtAsc(String recipient, String roomId);
}
