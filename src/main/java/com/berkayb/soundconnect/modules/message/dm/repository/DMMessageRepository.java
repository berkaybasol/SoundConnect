package com.berkayb.soundconnect.modules.message.dm.repository;

import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DMMessageRepository extends JpaRepository<DMMessage, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from DMMessage m where m.id = :id")
	Optional<DMMessage> findByIdForUpdate(@Param("id") UUID id);

	@Query("select m.conversationId from DMMessage m where m.id = :id")
	Optional<UUID> findConversationIdByMessageId(@Param("id") UUID id);

	@Modifying(flushAutomatically = true)
	@Query("delete from DMMessage m where m.conversationId = :conversationId")
	int deleteAllByConversationId(@Param("conversationId") UUID conversationId);

	// belirli bir konusmadaki mesajlari tarihe gore sirali don
	@Query("select m from DMMessage m where m.conversationId = :conversationId and m.deletedAt is null order by m.createdAt, m.id")
	List<DMMessage> findByConversationIdOrderByCreatedAtAsc(@Param("conversationId") UUID conversationId);

	@Query("select m from DMMessage m where m.conversationId = :conversationId and m.deletedAt is null")
	Page<DMMessage> findByConversationId(@Param("conversationId") UUID conversationId, Pageable pageable);

	// konusmadaki son mesaji getir (en yeni mesaj)
	Optional<DMMessage> findTopByConversationIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(UUID conversationId);

	default Optional<DMMessage> findTopByConversationIdOrderByCreatedAtDesc(UUID conversationId) {
		return findTopByConversationIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(conversationId);
	}

	// bir kullaniciya ait okunmamis mesajlari getir
	@Query("select m from DMMessage m where m.recipientId = :recipientId and m.readAt is null and m.deletedAt is null and exists (select c.id from DMConversation c where c.id = m.conversationId)")
	List<DMMessage> findByRecipientIdAndReadAtIsNull(@Param("recipientId") UUID recipientId);

	@Query("select count(m) from DMMessage m where m.recipientId = :recipientId and m.readAt is null and m.deletedAt is null and exists (select c.id from DMConversation c where c.id = m.conversationId)")
	long countByRecipientIdAndReadAtIsNull(@Param("recipientId") UUID recipientId);

	// bir konusmadaki bir kullaniciya aiy okunmamis mesajlari getir
	@Query("select m from DMMessage m where m.conversationId = :conversationId and m.recipientId = :recipientId and m.readAt is null and m.deletedAt is null and exists (select c.id from DMConversation c where c.id = m.conversationId)")
	List<DMMessage> findByConversationIdAndRecipientIdAndReadAtIsNull(@Param("conversationId") UUID conversationId, @Param("recipientId") UUID recipientId);
}
