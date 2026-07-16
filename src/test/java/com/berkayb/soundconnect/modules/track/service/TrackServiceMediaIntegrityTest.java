package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.mapper.TrackMapper;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackServiceMediaIntegrityTest {
	@Mock TrackRepository trackRepository;
	@Mock TrackMapper trackMapper;
	@Mock MediaAssetService mediaAssetService;
	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock MusicianProfileService musicianProfileService;
	@Mock BandService bandService;
	@InjectMocks TrackServiceImpl service;

	private UUID ownerId;
	private UUID userId;
	private UUID assetId;
	private TrackCreateRequestDto request;

	@BeforeEach
	void setUp() {
		ownerId = UUID.randomUUID();
		userId = UUID.randomUUID();
		assetId = UUID.randomUUID();
		request = new TrackCreateRequestDto(assetId, "Track", 180, 120);

		MusicianProfile profile = mock(MusicianProfile.class);
		User user = mock(User.class);
		when(profile.getUser()).thenReturn(user);
		when(user.getId()).thenReturn(userId);
		when(musicianProfileService.getProfileEntity(ownerId)).thenReturn(profile);
		when(bandService.getBandEntity(ownerId)).thenThrow(new SoundConnectException(ErrorType.BAND_NOT_FOUND));
	}

	@Test
	void createTrack_acceptsReadyPublicAudioOwnedByProfile() {
		MediaAsset asset = asset(MediaStatus.READY, MediaVisibility.PUBLIC, ownerId);
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		TrackResponseDto expected = new TrackResponseDto(null, assetId, "Track", "url", 180, 120);
		when(trackMapper.toDto(any(Track.class), same(mediaAssetService))).thenReturn(expected);

		assertThat(service.createTrack(ownerId, userId, request)).isSameAs(expected);
		verify(trackRepository).save(any(Track.class));
		verify(mediaAssetRepository).findByIdForUpdate(assetId);
	}

	@Test
	void createTrackReturnsFirstWriterOnReplayEvenIfTheAssetStateLaterChanges() {
		MediaAsset asset = asset(MediaStatus.FAILED, MediaVisibility.PRIVATE, ownerId);
		Track firstWriter = Track.builder()
				.id(UUID.randomUUID())
				.ownerType(TrackOwnerType.MUSICIAN_PROFILE)
				.ownerId(ownerId)
				.mediaAssetId(assetId)
				.title("First writer title")
				.durationSeconds(180)
				.bpm(120)
				.build();
		TrackResponseDto expected = new TrackResponseDto(
				firstWriter.getId(), assetId, firstWriter.getTitle(), "url", 180, 120);

		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		when(trackRepository.findByOwnerTypeAndOwnerIdAndMediaAssetId(
				TrackOwnerType.MUSICIAN_PROFILE, ownerId, assetId))
				.thenReturn(Optional.of(firstWriter));
		when(trackMapper.toDto(firstWriter, mediaAssetService)).thenReturn(expected);

		TrackResponseDto replay = service.createTrack(
				ownerId, userId, new TrackCreateRequestDto(assetId, "Retry title", 999, 1));

		assertThat(replay).isSameAs(expected);
		assertThat(replay.title()).isEqualTo("First writer title");
		verify(trackRepository, never()).save(any());
	}

	@Test
	void createTrack_rejectsAudioOwnedByAnotherProfile() {
		when(mediaAssetRepository.findByIdForUpdate(assetId))
				.thenReturn(Optional.of(asset(MediaStatus.READY, MediaVisibility.PUBLIC, UUID.randomUUID())));

		assertError(ErrorType.MEDIA_ASSET_OWNER_MISMATCH);
		verify(trackRepository, never()).save(any());
	}

	@Test
	void createTrack_rejectsIncompleteAudio() {
		when(mediaAssetRepository.findByIdForUpdate(assetId))
				.thenReturn(Optional.of(asset(MediaStatus.UPLOADING, MediaVisibility.PUBLIC, ownerId)));

		assertError(ErrorType.MEDIA_ASSET_NOT_READY);
		verify(trackRepository, never()).save(any());
	}

	@Test
	void createTrack_rejectsPrivateAudioBecauseTracksArePubliclyReadable() {
		when(mediaAssetRepository.findByIdForUpdate(assetId))
				.thenReturn(Optional.of(asset(MediaStatus.READY, MediaVisibility.PRIVATE, ownerId)));

		assertError(ErrorType.MEDIA_ASSET_NOT_PUBLIC);
		verify(trackRepository, never()).save(any());
	}

	private MediaAsset asset(MediaStatus status, MediaVisibility visibility, UUID mediaOwnerId) {
		return MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.AUDIO)
				.status(status)
				.visibility(visibility)
				.ownerType(MediaOwnerType.MUSICIAN_PROFILE)
				.ownerId(mediaOwnerId)
				.mimeType("audio/mpeg")
				.size(100L)
				.build();
	}

	private void assertError(ErrorType expected) {
		assertThatThrownBy(() -> service.createTrack(ownerId, userId, request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(expected));
	}
}
