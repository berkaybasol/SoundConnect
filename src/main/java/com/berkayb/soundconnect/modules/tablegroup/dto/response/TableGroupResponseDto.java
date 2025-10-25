package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record TableGroupResponseDto(
		UUID id,
		
		UUID ownerId,
		
		UUID venueId,
		
		String venueName,
		
		int maxPersonCount,
		
		List<String> genderPrefs,
		
		int ageMin,
		
		int ageMax,
		
		LocalDateTime expiresAt,
		
		TableGroupStatus status,
		
		Set<TableGroupParticipantDto> participants, // katilimci dto'su
		
		LocationDto city,
		LocationDto district,
		LocationDto neighborhood
		
) {
	@Builder
	public record LocationDto(UUID id, String name) {}
}