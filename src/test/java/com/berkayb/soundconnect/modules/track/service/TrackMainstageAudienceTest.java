package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.media.enums.MediaContentAudience;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.repository.TrackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackMainstageAudienceTest {
    @Mock TrackRepository tracks;
    @Mock MediaAssetService media;
    @InjectMocks TrackServiceImpl service;
    final UUID owner = UUID.randomUUID(), musicId = UUID.randomUUID(), changedId = UUID.randomUUID();

    @AfterEach void clearViewer() { SecurityContextHolder.clearContext(); }

    @Test void listenerProfileListUsesFilteredSupplyAndOmitsTitlesAfterConcurrentAudienceChange() {
        listener();
        var music = track(musicId, "Performance");
        var changed = track(changedId, "Business brief");
        when(tracks.findMainstageByOwner(owner, TrackOwnerType.MUSICIAN_PROFILE)).thenReturn(List.of(music, changed));
        when(media.getPlaybackUrlMap(List.of(musicId, changedId))).thenReturn(Map.of(musicId, "music-url"));
        when(media.getContentAudienceMap(List.of(musicId, changedId)))
                .thenReturn(Map.of(musicId, MediaContentAudience.MAINSTAGE));
        assertThat(service.getTracksByOwner(owner, TrackOwnerType.MUSICIAN_PROFILE))
                .singleElement().satisfies(dto -> {
                    assertThat(dto.title()).isEqualTo("Performance");
                    assertThat(dto.contentAudience()).isEqualTo(MediaContentAudience.MAINSTAGE);
                });
        verify(tracks, never()).findAllByOwnerIdAndOwnerType(any(), any());
    }

    @Test void listenerPaginatedListDoesNotExposeBusinessTitlesWhenUrlRecheckDropsTheAsset() {
        listener();
        var page = PageRequest.of(0, 20);
        when(tracks.findMainstageByOwner(owner, TrackOwnerType.BAND, page))
                .thenReturn(new PageImpl<>(List.of(track(changedId, "Business brief")), page, 1));
        when(media.getPlaybackUrlMap(List.of(changedId))).thenReturn(Map.of());
        when(media.getContentAudienceMap(List.of(changedId))).thenReturn(Map.of());
        assertThat(service.listTracks(owner, TrackOwnerType.BAND, page).getContent()).isEmpty();
        verify(tracks, never()).findByOwnerIdAndOwnerType(any(), any(), any());
    }

    @Test void backstageProfileListRetainsBusinessTracksAndCanonicalAudience() {
        when(tracks.findAllByOwnerIdAndOwnerType(owner, TrackOwnerType.MUSICIAN_PROFILE))
                .thenReturn(List.of(track(changedId, "Business brief")));
        when(media.getPlaybackUrlMap(List.of(changedId))).thenReturn(Map.of(changedId, "business-url"));
        when(media.getContentAudienceMap(List.of(changedId)))
                .thenReturn(Map.of(changedId, MediaContentAudience.BACKSTAGE));
        assertThat(service.getTracksByOwner(owner, TrackOwnerType.MUSICIAN_PROFILE))
                .singleElement().satisfies(dto -> assertThat(dto.contentAudience()).isEqualTo(MediaContentAudience.BACKSTAGE));
    }

    private Track track(UUID asset, String title) {
        return Track.builder().id(UUID.randomUUID()).ownerId(owner).ownerType(TrackOwnerType.MUSICIAN_PROFILE)
                .mediaAssetId(asset).title(title).build();
    }
    private void listener() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "viewer", "n/a", List.of(new SimpleGrantedAuthority("ROLE_LISTENER"))));
    }
}
