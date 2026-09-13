package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.*;
import com.berkayb.soundconnect.modules.promotion.announcement.AnnouncementAccess;
import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementMediaAccessTest {
    @Mock MediaAssetRepository mediaAssetRepository;
    @Mock StorageClient storageClient;
    @Mock MediaPolicy mediaPolicy;
    @Mock AnnouncementAccess announcementAccess;
    @InjectMocks MediaAssetServiceImpl service;
    final UUID viewer = UUID.randomUUID(), announcement = UUID.randomUUID(), assetId = UUID.randomUUID();

    @Test void authorizedViewerReceivesFreshProtectedMp4AndThumbnailWithExpiry() {
        MediaAsset asset = asset(MediaKind.VIDEO);
        when(mediaAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn("media/" + assetId + "/hls");
        Instant expiry = Instant.now().plusSeconds(300);
        String prefix = "protected/media/" + assetId + "/hls/";
        when(storageClient.createPresignedGetUrl(prefix + "video.mp4")).thenReturn(new StorageAccessUrl("https://private.invalid/video?signature=one", expiry));
        when(storageClient.createPresignedGetUrl(prefix + "thumbnail.jpg")).thenReturn(new StorageAccessUrl("https://private.invalid/thumbnail?signature=two", expiry));
        var response = service.createOwnerAccessUrl(viewer, assetId);
        assertThat(response.accessUrl()).contains("signature=one");
        assertThat(response.thumbnailAccessUrl()).contains("signature=two");
        assertThat(response.expiresAt()).isEqualTo(expiry);
        assertThat(response.streamingProtocol()).isEqualTo(MediaStreamingProtocol.PROGRESSIVE);
        var order = inOrder(announcementAccess, storageClient);
        order.verify(announcementAccess).requireMediaAccess(viewer, announcement, assetId);
        order.verify(storageClient).createPresignedGetUrl(prefix + "video.mp4");
        assertThat(asset.getPlaybackUrl()).isNull(); assertThat(asset.getThumbnailUrl()).isNull();
    }

    @Test void ArchivedRetargetedOrRetiredMediaCannotReachStorageSigning() {
        when(mediaAssetRepository.findById(assetId)).thenReturn(Optional.of(asset(MediaKind.IMAGE)));
        doThrow(new SoundConnectException(ErrorType.ANNOUNCEMENT_NOT_FOUND)).when(announcementAccess)
                .requireMediaAccess(viewer, announcement, assetId);
        assertThatThrownBy(() -> service.createOwnerAccessUrl(viewer, assetId)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(storageClient, mediaPolicy);
    }

    @Test void privateVideoWithoutNormalizedReadyOutputNeverSignsItsOriginalSource() {
        MediaAsset asset = asset(MediaKind.VIDEO); asset.setStreamingProtocol(MediaStreamingProtocol.HLS);
        when(mediaAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        assertThatThrownBy(() -> service.createOwnerAccessUrl(viewer, assetId)).isInstanceOfSatisfying(SoundConnectException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_STATE_INVALID));
        verifyNoInteractions(storageClient, mediaPolicy);
    }

    private MediaAsset asset(MediaKind kind) {
        return MediaAsset.builder().id(assetId).ownerType(MediaOwnerType.PROMOTION).ownerId(announcement)
                .visibility(MediaVisibility.PRIVATE).kind(kind).status(MediaStatus.READY)
                .streamingProtocol(MediaStreamingProtocol.PROGRESSIVE)
                .storageKey("protected/private-verified/media/" + assetId + "/source.mp4").build();
    }
}
