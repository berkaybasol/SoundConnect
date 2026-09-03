package com.berkayb.soundconnect.modules.tablegroup.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
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
		LocationDto neighborhood,

		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode ownerVisibilityMode
		
) {
	public TableGroupResponseDto {
		ownerVisibilityMode = ghostOnly(ownerVisibilityMode);
	}

	public TableGroupResponseDto(
			UUID id,
			UUID ownerId,
			String ownerUsername,
			String ownerProfileImageUrl,
			UUID venueId,
			String venueName,
			String description,
			int maxPersonCount,
			List<String> genderPrefs,
			int ageMin,
			int ageMax,
			Instant startAt,
			Instant meetingAt,
			Instant expiresAt,
			TableGroupStatus status,
			Set<TableGroupParticipantDto> participants,
			LocationDto city,
			LocationDto district,
			LocationDto neighborhood
	) {
		this(
				id,
				ownerId,
				ownerUsername,
				ownerProfileImageUrl,
				venueId,
				venueName,
				description,
				maxPersonCount,
				genderPrefs,
				ageMin,
				ageMax,
				startAt,
				meetingAt,
				expiresAt,
				status,
				participants,
				city,
				district,
				neighborhood,
				null
		);
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode visibilityMode) {
		return visibilityMode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}

	@Builder
	public record LocationDto(UUID id, String name) {}
}
