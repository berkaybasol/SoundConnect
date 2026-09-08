package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The existing batch boundary must never expose unavailable/private avatars. */
@ExtendWith(MockitoExtension.class)
class BandPendingInvitationsAvatarVisibilityTest {
    @Mock MediaAssetRepository assets;
    @InjectMocks MediaAssetServiceImpl media;

    @Test void displayBatchUsesCanonicalThumbnailAndOmitsMissingOrUnusableAssets() {
        var ready = image(MediaStatus.READY, MediaVisibility.PUBLIC);
        var noDisplay = image(MediaStatus.READY, MediaVisibility.PUBLIC);
        noDisplay.setThumbnailUrl(" "); noDisplay.setPlaybackUrl(null);
        UUID missingId = UUID.randomUUID();
        when(assets.findAllById(List.of(ready.getId(), noDisplay.getId(), missingId)))
                .thenReturn(List.of(ready, noDisplay));
        assertThat(media.getDisplayUrlMap(List.of(ready.getId(), noDisplay.getId(), missingId, ready.getId())))
                .containsOnly(entry(ready.getId(), "https://cdn.test/musician-thumbnail.jpg"));
        verify(assets).findAllById(List.of(ready.getId(), noDisplay.getId(), missingId));
        verifyNoMoreInteractions(assets);
    }

    @ParameterizedTest @EnumSource(value = MediaStatus.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void nonReadyAvatarAlwaysRemainsPlaceholder(MediaStatus status) {
        var asset = image(status, MediaVisibility.PUBLIC);
        when(assets.findAllById(List.of(asset.getId()))).thenReturn(List.of(asset));
        assertThat(media.getDisplayUrlMap(List.of(asset.getId()))).isEmpty();
    }

    @ParameterizedTest @EnumSource(value = MediaVisibility.class, names = "PUBLIC", mode = EnumSource.Mode.EXCLUDE)
    void privateOrUnlistedAvatarAlwaysRemainsPlaceholder(MediaVisibility visibility) {
        var asset = image(MediaStatus.READY, visibility);
        when(assets.findAllById(List.of(asset.getId()))).thenReturn(List.of(asset));
        assertThat(media.getDisplayUrlMap(List.of(asset.getId()))).isEmpty();
    }

    @Test void emptyBatchNeverReadsMediaRepository() {
        assertThat(media.getDisplayUrlMap(List.of())).isEmpty();
        verifyNoInteractions(assets);
    }

    private MediaAsset image(MediaStatus status, MediaVisibility visibility) {
        return MediaAsset.builder().id(UUID.randomUUID()).kind(MediaKind.IMAGE).status(status).visibility(visibility)
                .thumbnailUrl("https://cdn.test/musician-thumbnail.jpg")
                .playbackUrl("https://cdn.test/musician-full.jpg").build();
    }
}
