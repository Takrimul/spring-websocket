package com.example.chat.repository;

import com.example.chat.entity.DirectMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DirectMessageRepository extends JpaRepository<DirectMessageEntity, UUID> {
    List<DirectMessageEntity> findTop100BySenderPhoneAndReceiverPhoneOrderByCreatedAtAsc(
            String senderPhone, String receiverPhone
    );

    List<DirectMessageEntity> findTop100ByReceiverPhoneAndSenderPhoneOrderByCreatedAtAsc(
            String receiverPhone, String senderPhone
    );

    List<DirectMessageEntity> findByReceiverPhoneAndStatusOrderByCreatedAtAsc(String receiverPhone, String status);

    Optional<DirectMessageEntity> findByIdAndReceiverPhone(UUID id, String receiverPhone);
}
