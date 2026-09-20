package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionRequestedEvent;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionProperties;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps marketplace media on the existing durable deletion worker and recovery path. */
@Service
@RequiredArgsConstructor
@Transactional
public class MarketplaceMediaLifecycle {
    private final MarketplaceMediaAccess access;
    private final NamedParameterJdbcTemplate jdbc;
    private final MediaAssetRepository assets;
    private final MediaAssetReferenceGuard references;
    private final ApplicationEventPublisher events;
    private final MediaUploadAbuseGuard abuse;
    private final MediaDeletionProperties deletionProperties;

    public void validateAttachments(UUID actor, UUID listing, List<UUID> ids) {
        if (!access.canManage(actor, listing)) throw invalid();
        if (ids == null || ids.size() > 8 || ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            throw invalid();
        }
        // Stable order matches deletion and avoids lock inversion on photo reorder.
        for (UUID id : ids.stream().sorted().toList()) {
            MediaAsset asset = assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing)
                    .orElseThrow(MarketplaceMediaLifecycle::invalid);
            if (asset.getStatus() != MediaStatus.READY || asset.getVisibility() != MediaVisibility.PRIVATE
                    || asset.getKind() != MediaKind.IMAGE || asset.getContentAudience() != MediaContentAudience.BACKSTAGE
                    || !StorageObjectKeys.isProtected(asset.getStorageKey())) throw invalid();
        }
    }

    public void deleteDetachedAssets(UUID actor, UUID listing, Collection<UUID> ids) {
        if (!access.canManage(actor, listing)) throw invalid();
        for (UUID id : ids.stream().distinct().sorted().toList()) requestDeletion(listing, id);
    }

    /** Trusted domain operation; caller holds the listing lock and has detached its photos. */
    public void deleteListingMedia(UUID listing) {
        for (UUID id : jdbc.queryForList("""
                select id from tbl_media_asset where owner_type='MARKETPLACE' and owner_id=:listing
                    and status<>'DELETION_PENDING' order by id
                """, Map.of("listing", listing), UUID.class)) requestDeletion(listing, id);
    }

    /** Called while the account row is locked, before its domain rows are removed. */
    public void purgeAccount(UUID userId) {
        for (UUID listing : jdbc.queryForList("""
                select id from tbl_marketplace_listing where owner_user_id=:user order by id for update
                """, Map.of("user", userId), UUID.class)) {
            jdbc.update("delete from tbl_marketplace_listing_photo where listing_id=:id", Map.of("id", listing));
            deleteListingMedia(listing);
            jdbc.update("delete from tbl_marketplace_listing where id=:id", Map.of("id", listing));
        }
    }

    /** One bounded candidate; checks are repeated under both aggregate and asset locks. */
    public void deleteAbandonedAsset(UUID listing, UUID assetId, LocalDateTime cutoff) {
        if (jdbc.queryForList("select id from tbl_marketplace_listing where id=:id for update",
                Map.of("id", listing), UUID.class).isEmpty()) return;
        MediaAsset asset = assets.findByIdAndOwnerForUpdate(assetId, MediaOwnerType.MARKETPLACE, listing).orElse(null);
        if (asset == null || asset.getStatus() != MediaStatus.READY || asset.getCreatedAt() == null
                || !asset.getCreatedAt().isBefore(cutoff)) return;
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_marketplace_listing_photo where media_asset_id=:id)
                """, Map.of("id", assetId), Boolean.class))) return;
        requestDeletion(listing, assetId);
    }

    private void requestDeletion(UUID listing, UUID id) {
        MediaAsset asset = assets.findByIdAndOwnerForUpdate(id, MediaOwnerType.MARKETPLACE, listing).orElse(null);
        if (asset == null || asset.getStatus() == MediaStatus.DELETION_PENDING) return;
        // Immutable report evidence survives seller edits, withdrawal and account deletion.
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from tbl_marketplace_report_photo where media_asset_id=:id)
                """, Map.of("id", id), Boolean.class))) return;
        references.assertNotReferenced(id);
        // A private thumbnail producer may already hold a READY snapshot. Retain
        // the durable delete marker beyond its bounded download/process/upload.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        asset.setStatus(MediaStatus.DELETION_PENDING);
        asset.setDeletionRequestedAt(now);
        asset.setPhysicalDeletionNotBefore(now.plus(deletionProperties.getPublicImageProducerGrace()));
        assets.save(asset);
        events.publishEvent(new MediaDeletionRequestedEvent(id));
        abuse.releaseAfterCommit(id);
    }

    private static SoundConnectException invalid() {
        return new SoundConnectException(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST);
    }
}
