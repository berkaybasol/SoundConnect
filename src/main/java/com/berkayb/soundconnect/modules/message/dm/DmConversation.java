package com.berkayb.soundconnect.modules.message.dm;

import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
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
public class DmConversation extends BaseEntity {
	/**
	 * kullaniciya gelen son mesajin snapshotu(ozeti). (liste ekraninda hizli gosterim icin)
	 * Ilk N karakterini snapshot olarak sakliyoruz servis katmaninda setleyerek kesicez.
	 */
	@Column(name = "last_message_text_snapshot", length = 50)
	private String lastMessageTextSnapshot;
	
	// son mesajin olustugu zaman. inbox'da siralama icin kullanilcak. (DESC)
	@Column(name = "last_message_at")
	private Instant lastMessageAt; // instant = ?
	
	/**
	 * seq = mesajin sira numarasi.
	 * yeni bir mesaj geldiginde seq = maxSeq +1 atanir ve boylelikle mesajlar dogru sirayla dizilir.
	 * son mesaj en buyuk seq degerine sahip olur
	 */
	@Column(name = "max_seq", nullable = false)
	@Builder.Default // set edilmezse degeri 0 olarak baslat
	private Long maxSeq = 0L;
	
	
	
	
	
	
}