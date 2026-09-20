package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.marketplace.media.MarketplaceMediaAccess;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.StorageAccessUrl;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketplaceMediaSecurityTest {
    @Mock MediaAssetRepository mediaAssetRepository;
    @Mock MarketplaceMediaAccess marketplaceMediaAccess;
    @Mock StorageClient storageClient;
    @InjectMocks MediaAssetServiceImpl service;
    final UUID actor = UUID.randomUUID(), listing = UUID.randomUUID(), id = UUID.randomUUID();

    @Test void audienceCannotBeDowngradedToMainstage() {
        assertThat(MediaContentAudience.forOwner(MediaOwnerType.MARKETPLACE, MediaContentAudience.MAINSTAGE))
                .isEqualTo(MediaContentAudience.BACKSTAGE);
    }

    @Test void listenerOrStaleListingCannotReachStorageSigning() {
        when(mediaAssetRepository.findById(id)).thenReturn(Optional.of(asset()));
        doThrow(new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND)).when(marketplaceMediaAccess)
                .requireMediaAccess(actor, listing, id);
        assertThatThrownBy(() -> service.createOwnerAccessUrl(actor, id)).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(storageClient);
    }

    @Test void protectedOriginalIsSignedOnlyAfterListingAuthorization() {
        MediaAsset asset = asset();
        when(mediaAssetRepository.findById(id)).thenReturn(Optional.of(asset));
        Instant expires = Instant.now().plusSeconds(300);
        when(storageClient.createPresignedGetUrl(asset.getStorageKey()))
                .thenReturn(new StorageAccessUrl("https://private.invalid/image?signature=temporary", expires));
        var response = service.createOwnerAccessUrl(actor, id);
        assertThat(response.expiresAt()).isEqualTo(expires);
        var order = inOrder(marketplaceMediaAccess, storageClient);
        order.verify(marketplaceMediaAccess).requireMediaAccess(actor, listing, id);
        order.verify(storageClient).createPresignedGetUrl(asset.getStorageKey());
        assertThat(asset.getPlaybackUrl()).isNull();
        assertThat(response.thumbnailAccessUrl()).isNull();
    }

    @Test void protectedThumbnailUsesTheOriginalsAclAndOnlyItsOwnImmutableDirectory() {
        MediaAsset asset = asset();
        asset.setStorageKey("protected/private-verified/media/" + id + "/source.jpg");
        asset.setThumbnailStorageKey("protected/private-verified/media/" + id + "/thumbnail.jpg");
        when(mediaAssetRepository.findById(id)).thenReturn(Optional.of(asset));
        Instant expires = Instant.now().plusSeconds(300);
        when(storageClient.createPresignedGetUrl(asset.getStorageKey()))
                .thenReturn(new StorageAccessUrl("https://private.invalid/original", expires));
        when(storageClient.createPresignedGetUrl(asset.getThumbnailStorageKey()))
                .thenReturn(new StorageAccessUrl("https://private.invalid/thumbnail", expires));

        var response = service.createOwnerAccessUrl(actor, id);

        assertThat(response.thumbnailAccessUrl()).isEqualTo("https://private.invalid/thumbnail");
        assertThat(response.thumbnailExpiresAt()).isEqualTo(expires);
        var order = inOrder(marketplaceMediaAccess, storageClient);
        order.verify(marketplaceMediaAccess).requireMediaAccess(actor, listing, id);
        order.verify(storageClient).createPresignedGetUrl(asset.getStorageKey());
        order.verify(storageClient).createPresignedGetUrl(asset.getThumbnailStorageKey());
        verifyNoMoreInteractions(storageClient);
        assertThat(asset.getThumbnailUrl()).isNull();
    }

    @Test void malformedCrossAssetThumbnailIsNeverSignedAndOriginalRemainsReadable() {
        MediaAsset asset = asset();
        asset.setStorageKey("protected/private-verified/media/" + id + "/source.jpg");
        asset.setThumbnailStorageKey("protected/private-verified/media/other/thumbnail.jpg");
        when(mediaAssetRepository.findById(id)).thenReturn(Optional.of(asset));
        when(storageClient.createPresignedGetUrl(asset.getStorageKey()))
                .thenReturn(new StorageAccessUrl("https://private.invalid/original", Instant.now().plusSeconds(300)));

        assertThat(service.createOwnerAccessUrl(actor, id).thumbnailAccessUrl()).isNull();
        verify(storageClient, never()).createPresignedGetUrl(asset.getThumbnailStorageKey());
    }

    @Test void nonPrivateUploadIsRejectedBeforeCreatingAssetOrSignedUrl() {
        when(marketplaceMediaAccess.canManage(actor, listing)).thenReturn(true);
        assertThatThrownBy(() -> service.initUpload(actor, MediaOwnerType.MARKETPLACE, listing,
                MediaKind.IMAGE, MediaVisibility.PUBLIC, "image/jpeg", 100, "photo.jpg"))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(mediaAssetRepository, storageClient);
    }

    @Test void videoUploadIsRejectedBeforeCreatingAssetOrSignedUrl() {
        when(marketplaceMediaAccess.canManage(actor, listing)).thenReturn(true);
        assertThatThrownBy(() -> service.initUpload(actor, MediaOwnerType.MARKETPLACE, listing,
                MediaKind.VIDEO, MediaVisibility.PRIVATE, "video/mp4", 100, "clip.mp4"))
                .isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(mediaAssetRepository, storageClient);
    }

    @Test void genericPublicGalleryCannotExposeMarketplaceEvenWithMalformedLegacyVisibility() {
        assertThat(service.listPublicByOwner(MediaOwnerType.MARKETPLACE, listing, PageRequest.of(0, 20))).isEmpty();
        assertThat(service.listPublicByOwnerAndKind(MediaOwnerType.MARKETPLACE, listing, MediaKind.IMAGE, PageRequest.of(0, 20))).isEmpty();
        MediaAsset asset = asset();
        asset.setVisibility(MediaVisibility.PUBLIC);
        asset.setPlaybackUrl("https://public.invalid/accidental");
        when(mediaAssetRepository.findAllById(List.of(id))).thenReturn(List.of(asset));
        assertThat(service.getDisplayUrlMap(List.of(id))).isEmpty();
        assertThat(service.getPlaybackUrlMap(List.of(id))).isEmpty();
    }

    private MediaAsset asset() {
        return MediaAsset.builder().id(id).ownerType(MediaOwnerType.MARKETPLACE).ownerId(listing)
                .kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PRIVATE)
                .contentAudience(MediaContentAudience.BACKSTAGE).streamingProtocol(MediaStreamingProtocol.PROGRESSIVE)
                .storageKey("protected/media/" + id + "/source.jpg").build();
    }
}
