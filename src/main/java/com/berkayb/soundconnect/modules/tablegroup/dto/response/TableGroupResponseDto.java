package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record TableGroupResponseDto(
		UUID id,
		
		UUID ownerId,
		String ownerUsername,
		String ownerProfileImageUrl,
		
		// Nullable when the creator did not select a registered venue.
		UUID venueId,
		
		// Nullable when the creator did not specify any venue.
		String venueName,

		String description,
		
		int maxPersonCount,
		
		List<String> genderPrefs,
		
		int ageMin,
		
		int ageMax,

		// Server-owned lifecycle start instant.
		Instant startAt,

		// User-selected gathering time, shown in list/detail UI.
		Instant meetingAt,
		
		// Technical lifecycle cutoff; always 24 hours after creation.
		Instant expiresAt,
		
		TableGroupStatus status,
		
		Set<TableGroupParticipantDto> participants, // katilimci dto'su
		
		LocationDto city,
		LocationDto district,
		LocationDto neighborhood
		
) {
	@Builder
	public record LocationDto(UUID id, String name) {}
}
