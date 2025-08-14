package com.berkayb.soundconnect.modules.message.dm.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bu sinif iki kullanici arasindaki birebir sohbeti temsil eder.
 * her konusmada userA ve userB yer alir. bir konusma birden fazla mesaja sahip olabilir.
 */

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_dm_conversation",
		// indexes = butun veritabanini sorgulamak yerine direkt belirtilen yere gider. (bir kitabin icindekiler kismi gibi hayal et)
		indexes = {
				@Index(name = "idx_dm_conversation_last_message_at", columnList = "last_message_at DESC")
		}
)
public class DMConversation extends BaseEntity {
	
	@Column(nullable = false)
	private UUID userAId;
	
	@Column(nullable = false)
	private UUID userBId;
	
	@Column(name = "last_message_at")
	private LocalDateTime lastMessageAt; // konusmada gonderilen son mesaj tarihi. son yazismaya gore siralamak icin.
	
	@Column(name = "last_read_message_id")
	private UUID lastReadMessageId; // goruldu bilgisi icin
	

}