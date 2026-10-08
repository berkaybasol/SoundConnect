package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionRequestedEvent;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionProperties;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketplaceMediaLifecycleTest {
    @Mock MarketplaceMediaAccess access;
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock MediaAssetRepository assets;
    @Mock MediaAssetReferenceGuard references;
    @Mock ApplicationEventPublisher events;
    @Mock MediaUploadAbuseGuard abuse;
    @Spy MediaDeletionProperties deletionProperties = new MediaDeletionProperties();
    @InjectMocks MarketplaceMediaLifecycle lifecycle;
    final UUID actor = UUID.randomUUID(), listing = UUID.randomUUID(), id = UUID.randomUUID();

    @Test void crossListingOrMissingAttachmentFailsClosed() {
        when(access.canManage(actor, listing)).thenReturn(true);
        when(assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> lifecycle.validateAttachments(actor, listing, List.of(id)))
                .isInstanceOf(SoundConnectException.class);
    }

    @Test void incompleteOrPublicAssetCannotBeAttached() {
        when(access.canManage(actor, listing)).thenReturn(true);
        MediaAsset asset = asset();
        when(assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)).thenReturn(Optional.of(asset));
        asset.setStatus(MediaStatus.VERIFYING);
        assertThatThrownBy(() -> lifecycle.validateAttachments(actor, listing, List.of(id))).isInstanceOf(SoundConnectException.class);
        asset.setStatus(MediaStatus.READY);
        asset.setVisibility(MediaVisibility.PUBLIC);
        assertThatThrownBy(() -> lifecycle.validateAttachments(actor, listing, List.of(id))).isInstanceOf(SoundConnectException.class);
    }

    @Test void duplicateIdsCannotInflateAttachmentCountOrPositions() {
        when(access.canManage(actor, listing)).thenReturn(true);
        assertThatThrownBy(() -> lifecycle.validateAttachments(actor, listing, List.of(id, id))).isInstanceOf(SoundConnectException.class);
        verifyNoInteractions(assets);
    }

    @Test void emptyDraftAndReadyProtectedPhotoAreAccepted() {
        when(access.canManage(actor, listing)).thenReturn(true);
        lifecycle.validateAttachments(actor, listing, List.of());
        when(assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)).thenReturn(Optional.of(asset()));
        lifecycle.validateAttachments(actor, listing, List.of(id));
    }

    @Test void detachCreatesDurableIntentAndEventWithoutStorageIo() {
        when(access.canManage(actor, listing)).thenReturn(true);
        MediaAsset asset = asset();
        when(assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)).thenReturn(Optional.of(asset));
        lifecycle.deleteDetachedAssets(actor, listing, List.of(id));
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(asset.getPhysicalDeletionNotBefore())
                .isEqualTo(asset.getDeletionRequestedAt().plus(deletionProperties.getPublicImageProducerGrace()));
        var order = inOrder(references, assets, events, abuse);
        order.verify(references).assertNotReferenced(id);
        order.verify(assets).save(asset);
        order.verify(events).publishEvent(new MediaDeletionRequestedEvent(id));
        order.verify(abuse).releaseAfterCommit(id);
    }

    @Test void reportedEvidenceSurvivesSellerPhotoReplacement() {
        when(access.canManage(actor, listing)).thenReturn(true);
        MediaAsset asset = asset();
        when(assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)).thenReturn(Optional.of(asset));
        when(jdbc.queryForObject(contains("tbl_marketplace_report_photo"), anyMap(), eq(Boolean.class))).thenReturn(true);
        lifecycle.deleteDetachedAssets(actor, listing, List.of(id));
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.READY);
        verifyNoInteractions(events, abuse, references);
    }

    @Test void lateAttachmentWinsAgainstOrphanCleanup() {
        when(jdbc.queryForList(contains("for update"), anyMap(), eq(UUID.class))).thenReturn(List.of(listing));
        MediaAsset asset = asset(); asset.setCreatedAt(LocalDateTime.now().minusDays(3));
        when(assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)).thenReturn(Optional.of(asset));
        when(jdbc.queryForObject(contains("tbl_marketplace_listing_photo"), anyMap(), eq(Boolean.class))).thenReturn(true);
        lifecycle.deleteAbandonedAsset(listing, id, LocalDateTime.now().minusDays(1));
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.READY);
        verifyNoInteractions(events, abuse);
    }

    private MediaAsset asset() {
        return MediaAsset.builder().id(id).ownerType(MediaOwnerType.MARKETPLACE).ownerId(listing)
                .kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PRIVATE)
                .contentAudience(MediaContentAudience.BACKSTAGE).storageKey("protected/media/"+id+"/source.jpg").build();
    }
}
