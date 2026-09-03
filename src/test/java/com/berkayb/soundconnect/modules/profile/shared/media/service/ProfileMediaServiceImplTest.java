package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.repository.ProfileMediaRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ProfileMediaServiceImplTest {

	@Mock ProfileMediaRepository profileMediaRepository;
	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock BandRepository bandRepository;
	@Mock VenueRepository venueRepository;
	@Mock MusicianProfileRepository musicianProfileRepository;
	@Mock ProducerProfileRepository producerProfileRepository;
	@Mock OrganizerProfileRepository organizerProfileRepository;
	@Mock StudioProfileRepository studioProfileRepository;
	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock VenueProfileRepository venueProfileRepository;
	@Mock ListenerVisibilityPolicy listenerVisibilityPolicy;
	@InjectMocks ProfileMediaServiceImpl service;

	@Test
	void addMediaLocksAssetUntilProfileReferenceCommits() {
		UUID actingUserId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		User user = User.builder().id(actingUserId).build();
		ListenerProfile profile = ListenerProfile.builder().id(profileId).user(user).build();
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.LISTENER_PROFILE)
				.ownerId(profileId)
				.sourceUrl("https://cdn.example.test/gallery/image.jpg")
				.build();

		when(listenerProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		when(profileMediaRepository.save(any(ProfileMedia.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		ProfileMedia saved = service.addMedia(
				actingUserId, ProfileType.LISTENER, profileId, assetId,
				ProfileMediaRole.GALLERY, 0);

		assertThat(saved.getMediaAssetId()).isEqualTo(assetId);
		verify(mediaAssetRepository).findByIdForUpdate(assetId);
		verify(profileMediaRepository).save(saved);
	}

	@Test
	void addMediaReturnsTheExistingAttachmentBeforeRevalidatingLifecycleState() {
		UUID actingUserId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
				.id(profileId)
				.user(User.builder().id(actingUserId).build())
				.build();
		MediaAsset laterFailedAsset = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.VIDEO)
				.status(MediaStatus.FAILED)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.LISTENER_PROFILE)
				.ownerId(profileId)
				.build();
		ProfileMedia firstWriter = ProfileMedia.builder()
				.id(UUID.randomUUID())
				.profileType(ProfileType.LISTENER)
				.profileId(profileId)
				.mediaAssetId(assetId)
				.role(ProfileMediaRole.GALLERY)
				.orderIndex(4)
				.build();

		when(listenerProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(laterFailedAsset));
		when(profileMediaRepository.findByProfileTypeAndProfileIdAndMediaAssetIdAndRole(
				ProfileType.LISTENER, profileId, assetId, ProfileMediaRole.GALLERY))
				.thenReturn(Optional.of(firstWriter));

		ProfileMedia replay = service.addMedia(
				actingUserId, ProfileType.LISTENER, profileId, assetId,
				ProfileMediaRole.GALLERY, 99);

		assertThat(replay).isSameAs(firstWriter);
		assertThat(replay.getOrderIndex()).isEqualTo(4);
		verify(profileMediaRepository, never()).save(any());
	}

	@Test
	void ghostListenerCannotAddProfileMediaEvenWhenTheyOwnTheProfile() {
		UUID actingUserId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
				.id(profileId)
				.user(User.builder().id(actingUserId).build())
				.build();
		when(listenerProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
		when(listenerVisibilityPolicy.lockAndIsPubliclyRestrictedProfile(profileId)).thenReturn(true);

		assertThatThrownBy(() -> service.addMedia(
				actingUserId,
				ProfileType.LISTENER,
				profileId,
				assetId,
				ProfileMediaRole.GALLERY,
				0
		)).isInstanceOfSatisfying(SoundConnectException.class, exception ->
				assertThat(exception.getErrorType()).isEqualTo(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED));

		verify(mediaAssetRepository, never()).findByIdForUpdate(any());
		verify(profileMediaRepository, never()).save(any());
	}

	@Test
	void ghostListenerCannotRemoveFrozenProfileMedia() {
		UUID actingUserId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID profileMediaId = UUID.randomUUID();
		ListenerProfile profile = ListenerProfile.builder()
				.id(profileId)
				.user(User.builder().id(actingUserId).build())
				.build();
		ProfileMedia profileMedia = ProfileMedia.builder()
				.id(profileMediaId)
				.profileType(ProfileType.LISTENER)
				.profileId(profileId)
				.mediaAssetId(UUID.randomUUID())
				.role(ProfileMediaRole.GALLERY)
				.build();
		when(profileMediaRepository.findById(profileMediaId)).thenReturn(Optional.of(profileMedia));
		when(listenerProfileRepository.findById(profileId)).thenReturn(Optional.of(profile));
		when(listenerVisibilityPolicy.lockAndIsPubliclyRestrictedProfile(profileId)).thenReturn(true);

		assertThatThrownBy(() -> service.removeMedia(actingUserId, profileMediaId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED));

		verify(profileMediaRepository, never()).delete(any());
	}

	@Test
	void acceptsDurablyQueuedAndProcessingPublicVideos() {
		for (MediaStatus status : new MediaStatus[]{
				MediaStatus.TRANSCODE_QUEUED,
				MediaStatus.TRANSCODE_SENT,
				MediaStatus.PROCESSING
		}) {
			assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
					video(status, null), ProfileMediaRole.GALLERY))
					.as("status %s", status)
					.isTrue();
		}
	}

	@Test
	void readyVideoRequiresAPlaybackUrl() {
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				video(MediaStatus.READY, "https://cdn.example.test/video/master.m3u8"),
				ProfileMediaRole.FEATURED_VIDEO))
				.isTrue();
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				video(MediaStatus.READY, "  "), ProfileMediaRole.GALLERY))
				.isFalse();
	}

	@Test
	void acceptsOnlyReadyPublicImagesInGalleryRole() {
		MediaAsset image = MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.sourceUrl("https://cdn.example.test/gallery/image.jpg")
				.build();

		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				image, ProfileMediaRole.GALLERY)).isTrue();
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				image, ProfileMediaRole.FEATURED_VIDEO)).isFalse();

		image.setStatus(MediaStatus.PROCESSING);
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				image, ProfileMediaRole.GALLERY)).isFalse();
	}

	@Test
	void rejectsUnsafeOrTerminalFailureStates() {
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				video(MediaStatus.UPLOADING, null), ProfileMediaRole.GALLERY)).isFalse();
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				video(MediaStatus.FAILED, null), ProfileMediaRole.GALLERY)).isFalse();

		MediaAsset privateVideo = video(MediaStatus.PROCESSING, null);
		privateVideo.setVisibility(MediaVisibility.PRIVATE);
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				privateVideo, ProfileMediaRole.GALLERY)).isFalse();

		MediaAsset audio = video(MediaStatus.READY, "https://cdn.example.test/audio.mp3");
		audio.setKind(MediaKind.AUDIO);
		assertThat(ProfileMediaServiceImpl.isAttachableProfileMedia(
				audio, ProfileMediaRole.GALLERY)).isFalse();
	}

	private MediaAsset video(MediaStatus status, String playbackUrl) {
		return MediaAsset.builder()
				.kind(MediaKind.VIDEO)
				.status(status)
				.visibility(MediaVisibility.PUBLIC)
				.playbackUrl(playbackUrl)
				.build();
	}
}
