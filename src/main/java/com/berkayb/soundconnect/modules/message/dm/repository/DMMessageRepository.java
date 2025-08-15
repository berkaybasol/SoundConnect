package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DMMessageRepository extends JpaRepository<DMMessage, UUID> {
	
	// belirli bir konusmadaki mesajlari tarihe gore sirali don
	List<DMMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
	
	// konusmadaki son mesaji getir (en yeni mesaj)
	Optional<DMMessage> findTopByConversationIdOrderByCreatedAtDesc(UUID conversationId);
	
	// bir kullaniciya ait okunmamis mesajlari getir
	List<DMMessage> findByRecipientIdAndReadAtIsNull(UUID recipientId);
	
	// bir konusmadaki bir kullaniciya aiy okunmamis mesajlari getir
	List<DMMessage> findByConversationIdAndRecipientIdAndReadAtIsNull(UUID conversationId, UUID recipientId);
}