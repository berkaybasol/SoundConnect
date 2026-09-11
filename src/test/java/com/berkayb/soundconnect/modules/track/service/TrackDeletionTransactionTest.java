package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.mapper.TrackMapper;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
  "spring.datasource.url=jdbc:h2:mem:track-delete-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
  "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TrackServiceImpl.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TrackDeletionTransactionTest {
  @Autowired TrackServiceImpl service;
  @Autowired TrackRepository tracks;
  @Autowired MediaAssetRepository assets;
  @Autowired MediaAssetReferenceRepository references;
  @Autowired PlatformTransactionManager manager;
  @MockitoBean TrackMapper mapper;
  @MockitoBean MediaAssetService media;
  @MockitoBean MusicianProfileRepository musicians;
  @MockitoBean BandRepository bands;
  @MockitoBean BandMemberRepository bandMembers;
  @MockitoBean StudioProfileRepository studios;

  @Test void failedMediaDeletionRollsBackTheFlushedTrackRemoval() {
    UUID owner = UUID.randomUUID(), user = UUID.randomUUID();
    var track = new TransactionTemplate(manager).execute(status -> {
      var asset = assets.saveAndFlush(MediaAsset.builder().ownerId(owner).ownerType(MediaOwnerType.MUSICIAN_PROFILE)
          .kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
          .size(100L).mimeType("audio/mpeg").storageKey("test/" + UUID.randomUUID()).build());
      return tracks.saveAndFlush(Track.builder().ownerId(owner).ownerType(TrackOwnerType.MUSICIAN_PROFILE)
          .mediaAssetId(asset.getId()).title("Deletion rollback test").build());
    });
    when(musicians.findById(owner)).thenReturn(Optional.of(MusicianProfile.builder().user(User.builder().id(user).build()).build()));
    doAnswer(invocation -> {
      assertThat(references.countTrackReferences(track.getMediaAssetId())).isZero();
      throw new IllegalStateException("media deletion rejected");
    }).when(media).delete(track.getMediaAssetId(), user, MediaOwnerType.MUSICIAN_PROFILE, owner);
    assertThatThrownBy(() -> service.deleteTrack(track.getId(), owner, user, TrackOwnerType.MUSICIAN_PROFILE))
        .isInstanceOf(IllegalStateException.class).hasMessage("media deletion rejected");
    assertThat(tracks.existsById(track.getId())).isTrue();
    assertThat(assets.findById(track.getMediaAssetId()).orElseThrow().getStatus()).isEqualTo(MediaStatus.READY);
    assertThat(references.countTrackReferences(track.getMediaAssetId())).isEqualTo(1);
  }

	@Test
	void bandTrackCreationCommitsThroughTheRealTransactionalServiceProxy() {
		UUID owner = UUID.randomUUID(), user = UUID.randomUUID();
		MediaAsset asset = new TransactionTemplate(manager).execute(status -> assets.saveAndFlush(
				MediaAsset.builder()
						.ownerId(owner)
						.ownerType(MediaOwnerType.BAND)
						.kind(MediaKind.AUDIO)
						.status(MediaStatus.READY)
						.visibility(MediaVisibility.PUBLIC)
						.size(100L)
						.mimeType("audio/mpeg")
						.storageKey("test/" + UUID.randomUUID())
						.playbackUrl("https://example.test/track.mp3")
						.build()));
		when(musicians.existsById(owner)).thenReturn(false);
		when(bands.existsById(owner)).thenReturn(true);
		when(bandMembers.findByBandIdAndUserId(owner, user)).thenReturn(Optional.of(
				BandMember.builder()
						.status(BandMemberShipStatus.ACTIVE)
						.bandRole(BandRole.FOUNDER)
						.build()));
		when(mapper.toDto(any(Track.class), same(media))).thenAnswer(invocation -> {
			Track track = invocation.getArgument(0);
			return new TrackResponseDto(
					track.getId(), track.getMediaAssetId(), track.getTitle(),
					"https://example.test/track.mp3", track.getDurationSeconds(), track.getBpm());
		});

		TrackResponseDto created = service.createTrack(
				owner,
				user,
				new com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto(
						asset.getId(), "Band transaction", 1, 120));

		assertThat(created.title()).isEqualTo("Band transaction");
		assertThat(tracks.findByOwnerTypeAndOwnerIdAndMediaAssetId(
				TrackOwnerType.BAND, owner, asset.getId())).isPresent();
	}
}
