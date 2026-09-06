package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngagementTargetValidatorVenueOnlyTest {

	@Mock OverthinkingPostRepository overthinkingPostRepository;
	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock EventRepository eventRepository;
	@InjectMocks EngagementTargetValidatorImpl validator;

	@Test
	void venueEventRemainsAnEligibleEngagementTarget() {
		UUID eventId = UUID.randomUUID();
		when(eventRepository.existsByIdAndEventOrigin(eventId, EventOrigin.VENUE)).thenReturn(true);

		assertThatCode(() -> validator.validateExists(EngagementTargetType.EVENT, eventId))
				.doesNotThrowAnyException();

		verify(eventRepository).existsByIdAndEventOrigin(eventId, EventOrigin.VENUE);
		verify(eventRepository, never()).existsById(eventId);
		verifyNoInteractions(overthinkingPostRepository, mediaAssetRepository);
	}

	@Test
	void retainedMusicianEventCannotBeUsedForNewEngagement() {
		UUID eventId = UUID.randomUUID();
		// Model a row that still exists for schema/data compatibility, but is not
		// in the public venue-origin set. A raw existence check would admit it.
		when(eventRepository.existsByIdAndEventOrigin(eventId, EventOrigin.VENUE)).thenReturn(false);

		assertThatThrownBy(() -> validator.validateExists(EngagementTargetType.EVENT, eventId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.ENGAGEMENT_NOT_FOUND));

		verify(eventRepository).existsByIdAndEventOrigin(eventId, EventOrigin.VENUE);
		verify(eventRepository, never()).existsById(eventId);
		verifyNoInteractions(overthinkingPostRepository, mediaAssetRepository);
	}
}
