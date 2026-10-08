package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.modules.media.support.MediaJpaPostgresFixture;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises production Spring proxies with one JpaTransactionManager shared by JDBC and JPA. */
class MarketplaceMediaTransactionPostgresTest extends MediaJpaPostgresFixture {
    @Test
    void replacementCommitsOrderJpaDeletionFenceAndAfterCommitEventTogether() {
        var listing = draft();
        var removed = image(listing.id()); var kept = image(listing.id()); var added = image(listing.id());
        listing = update(listing, removed.getId(), kept.getId());

        var updated = update(listing, added.getId(), kept.getId());

        assertThat(photos(listing.id())).containsExactly(added.getId(), kept.getId());
        assertThat(updated.version()).isEqualTo(listing.version() + 1);
        var deleted = asset(removed.getId());
        assertThat(deleted.getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(deleted.getPhysicalDeletionNotBefore()).isAfter(deleted.getDeletionRequestedAt());
        assertThat(asset(kept.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(added.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).containsExactly(removed.getId());
        verify(context.getBean(MediaUploadAbuseGuard.class)).release(removed.getId());
    }

    @Test
    void deferredDatabaseCommitFailureRollsBackPhotoOrderVersionAndJpaDeleteIntent() {
        var draft = draft();
        var before = image(draft.id()); var replacement = image(draft.id());
        var listing = update(draft, before.getId());
        events.failAtCommit = true;

        assertThatThrownBy(() -> update(listing, replacement.getId())).isInstanceOf(RuntimeException.class);

        assertThat(photos(listing.id())).containsExactly(before.getId());
        assertThat(service.detail(seller, listing.id()).version()).isEqualTo(listing.version());
        assertThat(asset(before.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(before.getId()).getDeletionRequestedAt()).isNull();
        assertThat(asset(replacement.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).isEmpty();
        verify(context.getBean(MediaUploadAbuseGuard.class), never()).release(any());
        assertThat(jdbc.queryForObject("select count(*) from test_commit_guard", Map.of(), Long.class)).isZero();
    }

    @Test
    void draftDeletionRemovesJdbcRowsAndCommitsOneDurableIntentForEveryOwnedAsset() {
        var listing = draft();
        var attached = image(listing.id()); var uncommittedUpload = image(listing.id());
        listing = update(listing, attached.getId());

        service.deleteDraft(seller, listing.id(), listing.version());

        assertThat(photos(listing.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_listing where id=:id", Map.of("id", listing.id()), Long.class)).isZero();
        assertThat(asset(attached.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(asset(uncommittedUpload.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(events.committed).containsExactlyInAnyOrder(attached.getId(), uncommittedUpload.getId());
    }

    @Test
    void draftDeletionExceptionRestoresBothJdbcReferencesAndManagedEntityState() {
        var draft = draft(); var photo = image(draft.id()); var listing = update(draft, photo.getId());
        events.failImmediately = true;

        assertThatThrownBy(() -> service.deleteDraft(seller, listing.id(), listing.version()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(photos(listing.id())).containsExactly(photo.getId());
        assertThat(service.detail(seller, listing.id()).version()).isEqualTo(listing.version());
        assertThat(asset(photo.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).isEmpty();
        verify(context.getBean(MediaUploadAbuseGuard.class), never()).release(any());
    }

    @Test
    void lostUpdateResponseCannotCauseClientCleanupToDeleteTheCommittedReplacement() {
        var listing = draft(); var original = image(listing.id()); var replacement = image(listing.id());
        listing = update(listing, original.getId());
        update(listing, replacement.getId()); // Commit succeeds; the client never receives this response.
        UUID id = listing.id();

        assertThatThrownBy(() -> lifecycle.deleteDetachedAssets(seller, id, List.of(replacement.getId())))
                .isInstanceOf(SoundConnectException.class);

        assertThat(photos(id)).containsExactly(replacement.getId());
        assertThat(asset(replacement.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).containsExactly(original.getId());
    }

    @Test
    void reportSnapshotRetainsOldPhotoAfterReplacementAndAccountPurge() {
        var listing = draft(); var evidence = image(listing.id()); var replacement = image(listing.id());
        listing = update(listing, evidence.getId());
        UUID report = retainInReport(listing.id(), evidence.getId());
        update(listing, replacement.getId());
        assertThat(asset(evidence.getId()).getStatus()).isEqualTo(MediaStatus.READY);

        tx(() -> { listings.lockUser(seller); lifecycle.purgeAccount(seller); return null; });

        assertThat(photos(listing.id())).isEmpty();
        assertThat(asset(evidence.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(replacement.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(jdbc.queryForObject("select listing_id from tbl_marketplace_report where id=:id", Map.of("id", report), UUID.class)).isNull();
        assertThat(jdbc.queryForList("select media_asset_id from tbl_marketplace_report_photo where report_id=:id", Map.of("id", report), UUID.class))
                .containsExactly(evidence.getId());
        assertThatThrownBy(() -> context.getBean(MediaAssetReferenceGuard.class).assertNotReferenced(evidence.getId()))
                .isInstanceOf(SoundConnectException.class);
        assertThat(events.committed).containsExactly(replacement.getId());
    }

    @Test
    void accountPurgeCommitFailureRestoresAllListingsPhotoReferencesAndMediaStates() {
        var first = draft(); var a = image(first.id()); first = update(first, a.getId());
        var second = draft(); var b = image(second.id()); second = update(second, b.getId());
        events.failAtCommit = true;

        assertThatThrownBy(() -> tx(() -> { listings.lockUser(seller); lifecycle.purgeAccount(seller); return null; }))
                .isInstanceOf(RuntimeException.class);

        assertThat(photos(first.id())).containsExactly(a.getId());
        assertThat(photos(second.id())).containsExactly(b.getId());
        assertThat(asset(a.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(b.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).isEmpty();
        verify(context.getBean(MediaUploadAbuseGuard.class), never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Istanbul", "America/New_York"})
    void cleanupHonorsSame24HourAgeAsJpaRegardlessOfJvmZone(String zone) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        var listing = draft();
        var young = image(listing.id()); var old = image(listing.id());
        var attached = image(listing.id()); var evidence = image(listing.id());
        update(listing, attached.getId()); retainInReport(listing.id(), evidence.getId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        LocalDateTime oldAudit = now.minusHours(24).minusSeconds(30);
        age(young.getId(), now.minusHours(24).plusSeconds(30)); age(old.getId(), oldAudit);
        age(attached.getId(), now.minusDays(3)); age(evidence.getId(), now.minusDays(3));

        // Explicitly reproduce the raw-column/JPA distinction under the production binding.
        LocalDateTime raw = jdbc.queryForObject("select created_at from tbl_media_asset where id=:id",
                Map.of("id", old.getId()), (row, n) -> row.getObject(1, LocalDateTime.class));
        int offset = TimeZone.getDefault().toZoneId().getRules().getOffset(oldAudit).getTotalSeconds();
        assertThat(raw).isEqualTo(oldAudit.minusSeconds(offset));
        assertThat(asset(old.getId()).getCreatedAt()).isEqualTo(oldAudit);

        assertThat(assets.findMarketplaceOrphansBefore(now.minusHours(24), PageRequest.of(0, 100)))
                .extracting(MediaAssetRepository.MarketplaceOrphan::getAssetId).containsExactly(old.getId());
        new MarketplaceMediaCleanupScheduler(assets, lifecycle).cleanup();

        assertThat(asset(old.getId()).getStatus()).as("24h + 30s must be eligible in %s", zone)
                .isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(asset(young.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(attached.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(evidence.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).containsExactly(old.getId());
    }
}
