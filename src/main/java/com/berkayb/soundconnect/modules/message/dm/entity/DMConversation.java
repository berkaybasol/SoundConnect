package com.berkayb.soundconnect.modules.message.dm.entity;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.modules.message.dm.model.DmParticipantPair;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bu sinif iki kullanici arasindaki birebir sohbeti temsil eder.
 * her konusmada userA ve userB yer alir. bir konusma birden fazla mesaja sahip olabilir.
 * Production rollout requires a versioned data migration that merges any
 * existing reverse/duplicate pairs before adding the canonical check and
 * unique constraints declared below.
 */

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Check(constraints = "user_a_id < user_b_id")
@Table(
		name = "tbl_dm_conversation",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_dm_conversation_participant_pair",
						columnNames = {"user_a_id", "user_b_id"}
				)
		},
		// indexes = butun veritabanini sorgulamak yerine direkt belirtilen yere gider. (bir kitabin icindekiler kismi gibi hayal et)
		indexes = {
				@Index(name = "idx_dm_conversation_last_message_at", columnList = "last_message_at")
		}
)
public class DMConversation extends BaseEntity {
	
	@Column(name = "user_a_id", nullable = false)
	private UUID userAId;
	
	@Column(name = "user_b_id", nullable = false)
	private UUID userBId;
	
	@Column(name = "last_message_at")
	private LocalDateTime lastMessageAt; // konusmada gonderilen son mesaj tarihi. son yazismaya gore siralamak icin.
	
	@Column(name = "last_read_message_id")
	private UUID lastReadMessageId; // goruldu bilgisi icin

	@PrePersist
	@PreUpdate
	void canonicalizeParticipants() {
		DmParticipantPair pair = DmParticipantPair.of(userAId, userBId);
		userAId = pair.userAId();
		userBId = pair.userBId();
	}
	

}
