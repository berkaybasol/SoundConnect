package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

@Builder
public record TableGroupParticipantDto(
		UUID userId,
		LocalDateTime joinedAt,
		ParticipantStatus status
) {
}