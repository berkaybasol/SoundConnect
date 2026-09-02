package com.berkayb.soundconnect.modules.tablegroup.entity;

import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Bir masanin katilimcisi veya owner'i
 * Sadece TableGroup icinde kullanilir.
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Embeddable // baska entity icine gomulebilir oldugunu belirtiyoruz. (TableGroup)
@EqualsAndHashCode(of = "userId")
public class TableGroupParticipant {
	
	@Column(name = "user_id", nullable = false, columnDefinition = "uuid")
	private UUID userId;
	
	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt; // katilimci ne zaman eklendi?
	
	// katilimcinin durumu
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private ParticipantStatus status;
	
	@Column(name = "join_note", length = 256)
	private String joinNote;
	
}
