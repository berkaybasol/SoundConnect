package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DMConversationRepository extends JpaRepository<DMConversation, UUID> {
	
	// Kullanicinin dahil oldugu tum konusmalari getir
	List<DMConversation> findByUserAIdOrUserBId(UUID userAId, UUID userBId);
	
	// Iki kullanici arasinda var olan bir conversation varsa bulur
	@Query("SELECT c FROM DMConversation c WHERE (c.userAId = :userId1 AND c.userBId = :userId2) OR (c.userAId = :userId2 AND c.userBId = :userId1)")
	Optional<DMConversation> findConversationBetweenUsers(UUID userId1, UUID userId2);
	
	// Son mesaj tarihine gore conversationlari sirali cek
	List<DMConversation> findByUserAIdOrUserBIdOrderByLastMessageAtDesc(UUID userAId, UUID userBId);
	
}