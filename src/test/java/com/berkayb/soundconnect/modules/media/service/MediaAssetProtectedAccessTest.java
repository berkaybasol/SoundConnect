package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.dto.response.MediaAccessUrlResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionProperties;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageAccessUrl;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.verification.MediaUploadVerificationCoordinator;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetProtectedAccessTest {

	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock BandRepository bandRepository;
	@Mock VenueRepository venueRepository;
	@Mock MusicianProfileRepository musicianProfileRepository;
	@Mock ProducerProfileRepository producerProfileRepository;
	@Mock OrganizerProfileRepository organizerProfileRepository;
	@Mock StudioProfileRepository studioProfileRepository;
	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock VenueProfileRepository venueProfileRepository;
	@Mock StorageClient storageClient;
	@Mock MediaPolicy mediaPolicy;
	@Mock ApplicationEventPublisher applicationEventPublisher;
	@Mock MediaUploadFailureHandler mediaUploadFailureHandler;
	@Mock MediaUploadAbuseGuard mediaUploadAbuseGuard;
	@Mock MediaObjectPromotionCoordinator mediaObjectPromotionCoordinator;
	@Mock MediaUploadVerificationCoordinator mediaUploadVerificationCoordinator;
	@Mock PresignedUploadWriteWindow presignedUploadWriteWindow;
	@Mock MediaDeletionProperties mediaDeletionProperties;

	@InjectMocks MediaAssetServiceImpl service;

	@Test
	void initProtectedImageRoutesToPrivateOriginAndPersistsNoPublicUrl() {
		UUID ownerId = UUID.randomUUID();
		LocalDateTime authorityDeadline = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(16);
		when(mediaAssetRepository.save(any(MediaAsset.class))).thenAnswer(invocation -> {
			MediaAsset asset = invocation.getArgument(0);
			if (asset.getId() == null) asset.setId(UUID.randomUUID());
			return asset;
		});
		when(mediaPolicy.buildSourceKey(any(UUID.class), any(String.class)))
				.thenAnswer(invocation -> "media/" + invocation.getArgument(0) + "/source.png");
		when(storageClient.createPresignedPutUrl(any(String.class), any(String.class), anyLong()))
				.thenReturn("https://private-origin.example/upload-signature");
		when(presignedUploadWriteWindow.deadlineForNewSignature())
				.thenReturn(authorityDeadline);

		service.initUpload(
				ownerId, MediaOwnerType.USER, ownerId, MediaKind.IMAGE,
				MediaVisibility.PRIVATE, "image/png", 128L, "avatar.png"
		);
		verify(mediaUploadAbuseGuard).reserve(eq(ownerId), any(UUID.class), eq(128L));
		verify(mediaUploadAbuseGuard).releaseAfterRollback(any(UUID.class));

		ArgumentCaptor<MediaAsset> assetCaptor = ArgumentCaptor.forClass(MediaAsset.class);
		verify(mediaAssetRepository, org.mockito.Mockito.times(2)).save(assetCaptor.capture());
		MediaAsset persisted = assetCaptor.getAllValues().get(1);
		assertThat(persisted.getStorageKey()).startsWith("protected/media/");
		assertThat(persisted.getSourceUrl()).isNull();
		assertThat(persisted.getUploadWriteAuthorityExpiresAt()).isEqualTo(authorityDeadline);
		assertThat(persisted.getPlaybackUrl()).isNull();
		verify(storageClient, never()).publicUrl(any(String.class));
	}

	@Test
	void initProtectedVideoIsRejectedBeforeStorageOrPersistence() {
		UUID ownerId = UUID.randomUUID();

		assertThatThrownBy(() -> service.initUpload(
				ownerId, MediaOwnerType.USER, ownerId, MediaKind.VIDEO,
				MediaVisibility.UNLISTED, "video/mp4", 128L, "clip.mp4"
		))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST));

		verify(mediaAssetRepository, never()).save(any(MediaAsset.class));
		verify(storageClient, never()).createPresignedPutUrl(any(), any(), anyLong());
	}

	@Test
	void ownerCanMintShortLivedUrlForReadyProtectedProgressiveAsset() {
		UUID ownerId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		Instant expiresAt = Instant.parse("2030-01-01T00:05:00Z");
		MediaAsset asset = protectedAsset(assetId, ownerId);
		when(mediaAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
		when(storageClient.createPresignedGetUrl(asset.getStorageKey()))
				.thenReturn(new StorageAccessUrl("https://private-origin.example/signed-get", expiresAt));

		MediaAccessUrlResponseDto response = service.createOwnerAccessUrl(ownerId, assetId);

		assertThat(response.assetId()).isEqualTo(assetId);
		assertThat(response.accessUrl()).isEqualTo("https://private-origin.example/signed-get");
		assertThat(response.expiresAt()).isEqualTo(expiresAt);
	}

	@Test
	void nonOwnerCannotMintProtectedAccessUrl() {
		UUID ownerId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		when(mediaAssetRepository.findById(assetId)).thenReturn(Optional.of(protectedAsset(assetId, ownerId)));

		assertThatThrownBy(() -> service.createOwnerAccessUrl(UUID.randomUUID(), assetId))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_NOT_FOUND));
		verify(storageClient, never()).createPresignedGetUrl(any());
	}

	private MediaAsset protectedAsset(UUID assetId, UUID ownerId) {
		MediaAsset asset = MediaAsset.builder()
				.kind(MediaKind.AUDIO)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PRIVATE)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.storageKey("protected/media/" + assetId + "/source.mp3")
				.mimeType("audio/mpeg")
				.size(128L)
				.build();
		asset.setId(assetId);
		return asset;
	}
}
