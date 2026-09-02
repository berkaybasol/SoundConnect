package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record TableGroupParticipantDto(
		UUID userId,
		Instant joinedAt,
		ParticipantStatus status,
		String joinNote,
		String username, //eklendi
		String profilePictureUrl //eklendi
) {
}
