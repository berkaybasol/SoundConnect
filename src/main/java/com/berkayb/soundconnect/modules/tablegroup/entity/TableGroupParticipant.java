package com.berkayb.soundconnect.modules.tablegroup.entity;

import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.time.LocalDateTime;
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
public class TableGroupParticipant {
	
	private UUID userId;
	
	private LocalDateTime joinedAt; // katilimci ne zaman eklendi?
	
	// katilimcinin durumu
	@Enumerated(EnumType.STRING)
	private ParticipantStatus status;
	
}