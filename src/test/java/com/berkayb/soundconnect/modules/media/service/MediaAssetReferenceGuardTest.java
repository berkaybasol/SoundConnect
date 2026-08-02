package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.repository.MediaAssetReferenceRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetReferenceGuardTest {

	@Mock
	MediaAssetReferenceRepository repository;

	MediaAssetReferenceGuard guard;
	UUID assetId;

	@BeforeEach
	void setUp() {
		guard = new MediaAssetReferenceGuard(repository);
		assetId = UUID.randomUUID();
	}

	@Test
	void rejectsReferencedAssetWithStableDomainErrorAndShortCircuits() {
		when(repository.countTrackReferences(assetId)).thenReturn(1L);

		assertThatThrownBy(() -> guard.assertNotReferenced(assetId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						org.assertj.core.api.Assertions.assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_ASSET_IN_USE));

		verify(repository).countPromotionReferences(assetId);
		verify(repository).countTrackReferences(assetId);
		verifyNoMoreInteractions(repository);
	}

	@Test
	void checksEveryKnownLogicalReferenceBeforeAllowingDeletion() {
		assertThatCode(() -> guard.assertNotReferenced(assetId)).doesNotThrowAnyException();

		verify(repository).countPromotionReferences(assetId);
		verify(repository).countTrackReferences(assetId);
		verify(repository).countProfileMediaReferences(assetId);
		verify(repository).countMusicianProfilePictureReferences(assetId);
		verify(repository).countListenerProfilePictureReferences(assetId);
		verify(repository).countProducerProfilePictureReferences(assetId);
		verify(repository).countOrganizerProfilePictureReferences(assetId);
		verify(repository).countStudioProfilePictureReferences(assetId);
		verify(repository).countVenueProfilePictureReferences(assetId);
		verify(repository).countBandProfilePictureReferences(assetId);
		verify(repository).countStudioRoomPhotoReferences(assetId);
		verify(repository).countStudioEquipmentPhotoReferences(assetId);
		verify(repository).countEventPosterReferences(assetId.toString());
		verifyNoMoreInteractions(repository);
	}
}
