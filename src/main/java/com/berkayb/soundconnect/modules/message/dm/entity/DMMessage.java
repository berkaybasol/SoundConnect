package com.berkayb.soundconnect.modules.message.dm.entity;


import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bu sinif bir sohbetteki her gonderilen mesaji temsil eder.
 * her mesaj bir kukonusmaya ve iki kullaniciya baglidir.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_dm_message",
		indexes = { // indexes = butun veritabanini sorgulamak yerine direkt belirtilen yere gider. (bir kitabin icindekiler kismi gibi hayal et)
				// Belirli bir konuşmadaki tüm mesajları hızlıca çekmek için index
				@Index(name = "idx_dm_message_conversation_id", columnList = "conversation_id"),
				// Gönderen bazlı arama/sorgular için index
				@Index(name = "idx_dm_message_sender_id", columnList = "sender_id")
		}
)
public class DMMessage extends BaseEntity {
	
	// mesajin ait oldugu kkonusmanin (DMConversation) UUID bilgisi
	@Column(name = "conversation_id", nullable = false)
	private UUID conversationId;
	
	@Column(name = "sender_id", nullable = false)
	private UUID senderId; // gonderen id
	
	@Column(name = "recipient_id", nullable = false)
	private UUID recipientId; // alici id
	
	@Column(nullable = false, columnDefinition = "TEXT") // veritabanindaki tip text olsun
	private String content; // mesajin kendisi.
	
	@Column(name = "read_at")
	private LocalDateTime readAt; // mesaj ne zaman okundu?
	
	@Column(name = "deleted_at")
	private LocalDateTime deletedAt; // mesaj ne zaman silindi?
	
	@Column(name = "message_type", nullable = false)
	private String messageType; // mesaj tipi (text,image,file vs.. suan sadece text ama ilerisi icin)
	
	//TODO Media vs..
	
	
}