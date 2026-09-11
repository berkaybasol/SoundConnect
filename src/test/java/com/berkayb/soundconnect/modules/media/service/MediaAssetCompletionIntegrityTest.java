package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.image.ImageThumbnailService;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionProperties;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectMetadata;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.verification.MediaUploadVerificationCoordinator;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaAssetCompletionIntegrityTest {
	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock BandMemberRepository bandMemberRepository;
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
	@Mock ImageThumbnailService imageThumbnailService;
	@Mock PresignedUploadWriteWindow presignedUploadWriteWindow;
	@Mock MediaDeletionProperties mediaDeletionProperties;
	@InjectMocks MediaAssetServiceImpl service;

	private UUID ownerId;
	private MediaAsset asset;

	@BeforeEach
	void setUp() {
		ownerId = UUID.randomUUID();
		asset = MediaAsset.builder()
				.id(UUID.randomUUID())
				.kind(MediaKind.AUDIO)
				.status(MediaStatus.UPLOADING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("audio/x-m4a")
				.size(128L)
				.storageKey("quarantine/media/audio/source.m4a")
				.build();
		lenient().when(mediaAssetRepository.findByIdAndOwnerForUpdate(
				asset.getId(), MediaOwnerType.USER, ownerId
		)).thenReturn(Optional.of(asset));
		lenient().when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
	}

	@Test
	void validateAssignableMediaUsesDeletionCompatibleRowLock() {
		asset.setStatus(MediaStatus.READY);
		asset.setPlaybackUrl("https://cdn.test/audio.mp3");

		service.validateAssignableMedia(
				ownerId, asset.getId(), MediaOwnerType.USER, ownerId, MediaKind.AUDIO);

		verify(mediaAssetRepository).findByIdAndOwnerForUpdate(
				asset.getId(), MediaOwnerType.USER, ownerId
		);
	}

	@Test
	void validateAssignableMediaNeverLocksAnotherOwnersAsset() {
		UUID foreignAssetId = UUID.randomUUID();
		when(mediaAssetRepository.findByIdAndOwnerForUpdate(
				foreignAssetId, MediaOwnerType.USER, ownerId
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.validateAssignableMedia(
				ownerId, foreignAssetId, MediaOwnerType.USER, ownerId, MediaKind.IMAGE
		))
				.isInstanceOfSatisfying(SoundConnectException.class,
						error -> assertThat(error.getErrorType())
								.isEqualTo(ErrorType.MEDIA_ASSET_NOT_FOUND));

		verify(mediaAssetRepository).findByIdAndOwnerForUpdate(
				foreignAssetId, MediaOwnerType.USER, ownerId
		);
		verify(mediaAssetRepository, never()).findByIdForUpdate(foreignAssetId);
	}

	@Test
	void deleteNeverLocksAnotherOwnersAsset() {
		UUID foreignAssetId = UUID.randomUUID();
		when(mediaAssetRepository.findByIdAndOwnerForUpdate(
				foreignAssetId, MediaOwnerType.USER, ownerId
		)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.delete(
				foreignAssetId, ownerId, MediaOwnerType.USER, ownerId
		))
				.isInstanceOfSatisfying(SoundConnectException.class,
						error -> assertThat(error.getErrorType())
								.isEqualTo(ErrorType.MEDIA_ASSET_NOT_FOUND));

		verify(mediaAssetRepository).findByIdAndOwnerForUpdate(
				foreignAssetId, MediaOwnerType.USER, ownerId
		);
		verify(mediaAssetRepository, never()).findByIdForUpdate(foreignAssetId);
	}

	@Test
	void completeUpload_rejectsMissingStorageObject() {
		when(mediaUploadVerificationCoordinator.complete(
				asset.getId(), MediaOwnerType.USER, ownerId))
				.thenThrow(new SoundConnectException(ErrorType.MEDIA_STORAGE_OBJECT_NOT_FOUND));

		assertThatThrownBy(() -> service.completeUpload(ownerId, asset.getId()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.MEDIA_STORAGE_OBJECT_NOT_FOUND));
		verify(mediaUploadVerificationCoordinator).complete(
				asset.getId(), MediaOwnerType.USER, ownerId);
	}

	@Test
	void completeUpload_rejectsClientDeclaredSizeMismatch() {
		when(mediaUploadVerificationCoordinator.complete(
				asset.getId(), MediaOwnerType.USER, ownerId))
				.thenThrow(new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH));

		assertThatThrownBy(() -> service.completeUpload(ownerId, asset.getId()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH));
		verify(mediaUploadVerificationCoordinator).complete(
				asset.getId(), MediaOwnerType.USER, ownerId);
	}

	@Test
	void completeUpload_acceptsEquivalentM4aMimeAliasAndUsesAuthoritativeMetadata() {
		asset.setStatus(MediaStatus.READY);
		asset.setMimeType("audio/mp4");
		asset.setStorageKey("media/audio/source.m4a");
		asset.setPlaybackUrl("https://cdn.example/media/audio/source.m4a");
		when(mediaUploadVerificationCoordinator.complete(
				asset.getId(), MediaOwnerType.USER, ownerId)).thenReturn(asset);

		MediaAsset completed = service.completeUpload(ownerId, asset.getId());

		assertThat(completed.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(completed.getMimeType()).isEqualTo("audio/mp4");
		assertThat(completed.getPlaybackUrl()).isEqualTo("https://cdn.example/media/audio/source.m4a");
		assertThat(completed.getStorageKey()).isEqualTo("media/audio/source.m4a");
		verify(mediaUploadVerificationCoordinator).complete(
				asset.getId(), MediaOwnerType.USER, ownerId);
	}

	@Test
	void completeUpload_isIdempotentAfterReady() {
		asset.setStatus(MediaStatus.READY);
		asset.setStorageKey("media/audio/source.m4a");
		when(mediaUploadVerificationCoordinator.complete(
				asset.getId(), MediaOwnerType.USER, ownerId)).thenReturn(asset);

		assertThat(service.completeUpload(ownerId, asset.getId())).isSameAs(asset);
		verifyNoInteractions(storageClient, mediaPolicy, applicationEventPublisher);
		verify(mediaUploadVerificationCoordinator).complete(
				asset.getId(), MediaOwnerType.USER, ownerId);
	}

	@Test
	void completeUploadAuthorizesActiveBandManagerWithoutTraversingBandAggregate() {
		UUID actingUserId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		MediaAsset bandAsset = MediaAsset.builder()
				.id(UUID.randomUUID())
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.UPLOADING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.BAND)
				.ownerId(bandId)
				.mimeType("image/png")
				.size(128L)
				.storageKey("quarantine/media/band/avatar.png")
				.build();
		BandMember membership = BandMember.builder()
				.status(BandMemberShipStatus.ACTIVE)
				.bandRole(BandRole.MANAGER)
				.build();
		when(mediaAssetRepository.findById(bandAsset.getId())).thenReturn(Optional.of(bandAsset));
		when(bandMemberRepository.findByBandIdAndUserId(bandId, actingUserId))
				.thenReturn(Optional.of(membership));
		when(mediaUploadVerificationCoordinator.complete(
				bandAsset.getId(), MediaOwnerType.BAND, bandId)).thenReturn(bandAsset);

		assertThat(service.completeUpload(actingUserId, bandAsset.getId())).isSameAs(bandAsset);

		verify(bandMemberRepository).findByBandIdAndUserId(bandId, actingUserId);
		verify(mediaUploadVerificationCoordinator).complete(
				bandAsset.getId(), MediaOwnerType.BAND, bandId);
	}

	@Test
	void completeUploadRejectsBandMemberWithoutManagementRole() {
		UUID actingUserId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		MediaAsset bandAsset = MediaAsset.builder()
				.id(UUID.randomUUID())
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.UPLOADING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.BAND)
				.ownerId(bandId)
				.mimeType("image/png")
				.size(128L)
				.storageKey("quarantine/media/band/avatar.png")
				.build();
		BandMember membership = BandMember.builder()
				.status(BandMemberShipStatus.ACTIVE)
				.bandRole(BandRole.MEMBER)
				.build();
		when(mediaAssetRepository.findById(bandAsset.getId())).thenReturn(Optional.of(bandAsset));
		when(bandMemberRepository.findByBandIdAndUserId(bandId, actingUserId))
				.thenReturn(Optional.of(membership));

		assertThatThrownBy(() -> service.completeUpload(actingUserId, bandAsset.getId()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.FORBIDDEN_ACCESS));

		verifyNoInteractions(mediaUploadVerificationCoordinator);
	}

	@Test
	void completeUploadRejectsLegacyPublicBucketSourceBeforeInspection() {
		asset.setStorageKey("media/audio/source.m4a");
		when(mediaUploadVerificationCoordinator.complete(
				asset.getId(), MediaOwnerType.USER, ownerId))
				.thenThrow(new SoundConnectException(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH));

		assertThatThrownBy(() -> service.completeUpload(ownerId, asset.getId()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.MEDIA_UPLOAD_METADATA_MISMATCH));
		verifyNoInteractions(storageClient);
	}
}
