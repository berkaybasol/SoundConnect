package com.berkayb.soundconnect.modules.event.performer.service;

import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestResponseDto;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.shared.response.PageResponse;

import java.util.UUID;

public interface EventPerformerRequestService {

	void createPendingRequest(UUID venueOwnerUserId, Event event, MusicianProfile musician, Band band);

	void createProfileVisibilityRequest(UUID venueOwnerUserId, Event event, MusicianProfile musician, Band band);

	PageResponse<EventPerformerRequestResponseDto> getMine(
			UUID actorUserId,
			EventPerformerRequestStatus status,
			PerformerType targetType,
			UUID targetId,
			int page,
			int size
	);

	EventPerformerRequestResponseDto accept(UUID actorUserId, UUID requestId);

	EventPerformerRequestResponseDto accept(UUID actorUserId, UUID requestId, Boolean showOnProfile);

	EventPerformerRequestResponseDto reject(UUID actorUserId, UUID requestId);

	EventPerformerRequestResponseDto reconsider(UUID actorUserId, UUID requestId, Boolean showOnProfile);

	void deleteForEvent(UUID eventId);

	void invalidateForBand(UUID bandId);
}
