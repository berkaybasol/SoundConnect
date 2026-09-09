package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.mapper.TrackMapper;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TrackDeletionTest {
  @Mock TrackRepository tracks;
  @Mock TrackMapper mapper;
  @Mock MediaAssetService media;
  @Mock MusicianProfileService musicians;
  @Mock BandService bands;
  @Mock StudioProfileRepository studios;
  @Mock MediaAssetRepository assets;
  @InjectMocks TrackServiceImpl service;
  final UUID owner = UUID.randomUUID(), user = UUID.randomUUID(), asset = UUID.randomUUID(), id = UUID.randomUUID();
  Track track() { return Track.builder().id(id).ownerId(owner).ownerType(TrackOwnerType.MUSICIAN_PROFILE).mediaAssetId(asset).build(); }
  void owner() { when(musicians.getProfileEntity(owner)).thenReturn(MusicianProfile.builder().user(User.builder().id(user).build()).build()); }
  @Test void detachesAndFlushesBeforeGuardedMediaDeletion() {
    var track = track(); when(tracks.findById(id)).thenReturn(Optional.of(track)); owner();
    when(assets.findByIdAndOwnerForUpdate(asset, MediaOwnerType.MUSICIAN_PROFILE, owner)).thenReturn(Optional.of(MediaAsset.builder().id(asset).build()));
    service.deleteTrack(id, owner, user, TrackOwnerType.MUSICIAN_PROFILE);
    var order = inOrder(assets, tracks, media);
    order.verify(assets).findByIdAndOwnerForUpdate(asset, MediaOwnerType.MUSICIAN_PROFILE, owner);
    order.verify(tracks).delete(track); order.verify(tracks).flush();
    order.verify(media).delete(asset, user, MediaOwnerType.MUSICIAN_PROFILE, owner);
  }
  @Test void anotherUserCannotDetachOrDeleteMedia() {
    when(tracks.findById(id)).thenReturn(Optional.of(track())); owner();
    assertThatThrownBy(() -> service.deleteTrack(id, owner, UUID.randomUUID(), TrackOwnerType.MUSICIAN_PROFILE)).isInstanceOf(RuntimeException.class);
    verify(tracks, never()).delete(any()); verifyNoInteractions(assets, media);
  }
  @Test void missingAssetDoesNotDetachTrack() {
    when(tracks.findById(id)).thenReturn(Optional.of(track())); owner();
    when(assets.findByIdAndOwnerForUpdate(asset, MediaOwnerType.MUSICIAN_PROFILE, owner)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.deleteTrack(id, owner, user, TrackOwnerType.MUSICIAN_PROFILE)).isInstanceOf(RuntimeException.class);
    verify(tracks, never()).delete(any()); verifyNoInteractions(media);
  }
}
